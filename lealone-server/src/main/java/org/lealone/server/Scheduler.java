/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.server;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.lealone.common.concurrent.ScheduledExecutors;
import org.lealone.common.exceptions.DbException;
import org.lealone.common.logging.Logger;
import org.lealone.common.logging.LoggerFactory;
import org.lealone.common.util.DateTimeUtils;
import org.lealone.db.async.AsyncPeriodicTask;
import org.lealone.db.async.AsyncTask;
import org.lealone.db.async.AsyncTaskHandler;
import org.lealone.db.session.ServerSession;
import org.lealone.db.session.ServerSession.YieldableCommand;
import org.lealone.db.session.Session;
import org.lealone.net.AsyncConnection;
import org.lealone.net.nio.NioEventLoop;
import org.lealone.net.nio.NioEventLoopAdapter;
import org.lealone.sql.PreparedSQLStatement;
import org.lealone.sql.SQLStatementExecutor;
import org.lealone.storage.PageOperation;
import org.lealone.storage.PageOperationHandler;
import org.lealone.transaction.Transaction;

public class Scheduler extends Thread
        implements SQLStatementExecutor, PageOperationHandler, AsyncTaskHandler, Transaction.Listener, NioEventLoop {

    private static final Logger logger = LoggerFactory.getLogger(Scheduler.class);

    private final CopyOnWriteArrayList<SessionInfo> sessions = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<PageOperation> pageOperationQueue = new ConcurrentLinkedQueue<>();

    private final ConcurrentLinkedQueue<AsyncTask> minPriorityQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<AsyncTask> normPriorityQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<AsyncTask> maxPriorityQueue = new ConcurrentLinkedQueue<>();

    private final ConcurrentLinkedQueue<AsyncTask> sessionInitTaskQueue = new ConcurrentLinkedQueue<>();
    private final UserAndPasswordValidator userAndPasswordValidator = new UserAndPasswordValidator();

    // 这个只增不删所以用CopyOnWriteArrayList
    private final CopyOnWriteArrayList<AsyncTask> periodicQueue = new CopyOnWriteArrayList<>();

    private final Semaphore haveWork = new Semaphore(1);
    private final long loopInterval;
    private volatile boolean end;
    private volatile boolean waiting;
    private YieldableCommand nextBestCommand;

    public Scheduler(int id, Map<String, String> config) {
        super(ScheduleService.class.getSimpleName() + "-" + id);
        setDaemon(true);
        // 如果NetServer已经用了EventLoop那就不再用NioEventLoopAdapter
        boolean useEventLoop = Boolean.parseBoolean(config.get("use_event_loop"));
        if (!useEventLoop) {
            initNioEventLoopAdapter(config);
            loopInterval = 0;
        } else {
            // 默认100毫秒
            loopInterval = DateTimeUtils.getLoopInterval(config, "scheduler_loop_interval", 100);
        }
    }

    void addSessionInfo(SessionInfo si) {
        sessions.add(si);
    }

    void removeSessionInfo(SessionInfo si) {
        sessions.remove(si);
    }

    @Override
    public void run() {
        while (!end) {
            runSessionInitTasks();
            runQueueTasks(maxPriorityQueue);
            runQueueTasks(normPriorityQueue);
            runQueueTasks(minPriorityQueue);

            runPageOperationTasks();
            runSessionTasks();
            executeNextStatement();
        }
    }

    private void runQueueTasks(ConcurrentLinkedQueue<AsyncTask> queue) {
        AsyncTask task = queue.poll();
        while (task != null) {
            try {
                task.run();
            } catch (Throwable e) {
                logger.warn("Failed to run async queue task: " + task, e);
            }
            task = queue.poll();
        }
    }

    private void runPageOperationTasks() {
        PageOperation po = pageOperationQueue.poll();
        while (po != null) {
            try {
                po.run(this);
            } catch (Throwable e) {
                logger.warn("Failed to run page operation: " + po, e);
            }
            po = pageOperationQueue.poll();
        }
    }

    private void runSessionTasks() {
        if (sessions.isEmpty())
            return;
        // 不适合使用普通for循环，sessions会随时新增或删除元素，
        // 只能每次创建一个迭代器包含元素数组的快照
        for (SessionInfo si : sessions) {
            si.runSessionTasks();
        }
    }

    private void runSessionInitTasks() {
        if (userAndPasswordValidator.canHandNextSessionInitTask()) {
            AsyncTask task = sessionInitTaskQueue.poll();
            while (task != null) {
                try {
                    task.run();
                    if (!userAndPasswordValidator.canHandNextSessionInitTask()) {
                        break;
                    }
                } catch (Throwable e) {
                    logger.warn("Failed to run session init task: " + task, e);
                }
                task = sessionInitTaskQueue.poll();
            }
        }
    }

    public void validateUserAndPassword(boolean correct) {
        userAndPasswordValidator.validateUserAndPassword(correct);
    }

    void end() {
        end = true;
        wakeUp();
    }

    @Override
    public long getLoad() {
        return sessions.size();
    }

    @Override
    public void handlePageOperation(PageOperation po) {
        pageOperationQueue.add(po);
        wakeUp();
    }

    @Override
    public void handle(AsyncTask task) {
        if (task.isPeriodic()) {
            periodicQueue.add(task);
        } else {
            switch (task.getPriority()) {
            case AsyncTask.NORM_PRIORITY:
                normPriorityQueue.add(task);
                break;
            case AsyncTask.MAX_PRIORITY:
                maxPriorityQueue.add(task);
                break;
            case AsyncTask.MIN_PRIORITY:
                minPriorityQueue.add(task);
                break;
            default:
                normPriorityQueue.add(task);
            }
        }
        wakeUp();
    }

    public void handleSessionInitTask(AsyncTask task) {
        sessionInitTaskQueue.add(task);
        wakeUp();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(AsyncTask task, long initialDelay, long delay, TimeUnit unit) {
        return ScheduledExecutors.scheduledTasks.scheduleWithFixedDelay(task, initialDelay, delay, unit);
    }

    @Override
    public void addPeriodicTask(AsyncPeriodicTask task) {
        periodicQueue.add(task);
    }

    @Override
    public void removePeriodicTask(AsyncPeriodicTask task) {
        periodicQueue.remove(task);
    }

    @Override
    public void executeNextStatement() {
        int priority = PreparedSQLStatement.MIN_PRIORITY - 1; // 最小优先级减一，保证能取到最小的
        YieldableCommand last = null;
        while (true) {
            YieldableCommand c;
            if (nextBestCommand != null) {
                c = nextBestCommand;
                nextBestCommand = null;
            } else {
                c = getNextBestCommand(priority, true);
            }
            if (c == null) {
                checkSessionTimeout();
                handlePeriodicTasks();
                runPageOperationTasks();
                runSessionTasks();
                runQueueTasks(maxPriorityQueue);
                runQueueTasks(normPriorityQueue);
                c = getNextBestCommand(priority, true);
                if (c == null) {
                    doAwait();
                    break;
                }
            }
            try {
                c.run();
                // 说明没有新的命令了，一直在轮循
                if (last == c) {
                    runPageOperationTasks();
                    runSessionTasks();
                    runQueueTasks(maxPriorityQueue);
                    runQueueTasks(normPriorityQueue);
                }
                last = c;
            } catch (Throwable e) {
                for (SessionInfo si : sessions) {
                    if (si.getSessionId() == c.getSessionId()) {
                        si.sendError(c.getPacketId(), e);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public boolean yieldIfNeeded(PreparedSQLStatement current) {
        // 如果有新的session需要创建，那么先接入新的session
        runSessionInitTasks();

        // 如果来了更高优化级的命令，那么当前正在执行的语句就让出当前线程，
        // 当前线程转去执行高优先级的命令
        int priority = current.getPriority();
        nextBestCommand = getNextBestCommand(priority, false);
        if (nextBestCommand != null) {
            current.setPriority(priority + 1);
            return true;
        }
        return false;
    }

    private YieldableCommand getNextBestCommand(int priority, boolean checkTimeout) {
        if (sessions.isEmpty())
            return null;
        YieldableCommand best = null;
        for (SessionInfo si : sessions) {
            YieldableCommand c = si.getYieldableCommand(checkTimeout);
            if (c == null)
                continue;
            if (c.getPriority() > priority) {
                best = c;
                priority = c.getPriority();
            }
        }
        return best;
    }

    @Override
    public void wakeUp() {
        if (waiting)
            haveWork.release(1);
        // if (waiting)
        if (nioEventLoopAdapter != null)
            nioEventLoopAdapter.getSelector().wakeup();
    }

    @Override
    public Object addSession(Session session, Object parentSessionInfo) {
        SessionInfo parent = (SessionInfo) parentSessionInfo;
        SessionInfo si = parent.copy((ServerSession) session);
        ((ServerSession) session).setSessionInfo(si);
        addSessionInfo(si);
        return si;
    }

    @Override
    public void removeSession(Object sessionInfo) {
        SessionInfo si = (SessionInfo) sessionInfo;
        // 不删除root session
        if (!si.getSession().isRoot())
            removeSessionInfo(si);
    }

    private void checkSessionTimeout() {
        if (sessions.isEmpty())
            return;
        long currentTime = System.currentTimeMillis();
        for (SessionInfo si : sessions) {
            si.checkSessionTimeout(currentTime);
        }
    }

    private void handlePeriodicTasks() {
        if (periodicQueue.isEmpty())
            return;
        for (int i = 0, size = periodicQueue.size(); i < size; i++) {
            AsyncTask task = periodicQueue.get(i);
            try {
                task.run();
            } catch (Throwable e) {
                logger.warn("Failed to run periodic task: " + task + ", task index: " + i, e);
            }
        }
    }

    // 以下使用同步方式执行
    private AtomicInteger counter;
    private volatile RuntimeException e;

    @Override
    public void beforeOperation() {
        e = null;
        counter = new AtomicInteger(1);
    }

    @Override
    public void operationUndo() {
        counter.decrementAndGet();
        wakeUp();
    }

    @Override
    public void operationComplete() {
        counter.decrementAndGet();
        wakeUp();
    }

    @Override
    public void setException(RuntimeException e) {
        this.e = e;
    }

    @Override
    public RuntimeException getException() {
        return e;
    }

    @Override
    public void await() {
        for (;;) {
            if (counter.get() < 1)
                break;
            runQueueTasks(maxPriorityQueue);
            runQueueTasks(normPriorityQueue);
            runQueueTasks(minPriorityQueue);
            runPageOperationTasks();
            if (counter.get() < 1)
                break;
            doAwait();
        }
        if (e != null)
            throw e;
    }

    private void doAwait() {
        if (nioEventLoopAdapter == null) {
            waiting = true;
            try {
                haveWork.tryAcquire(loopInterval, TimeUnit.MILLISECONDS);
                haveWork.drainPermits();
            } catch (InterruptedException e) {
                handleInterruptedException(e);
            } finally {
                waiting = false;
            }
            return;
        }
        waiting = true;
        try {
            nioEventLoopAdapter.select();
        } catch (IOException e1) {
            logger.warn("Failed to select", e);
            return;
        }
        waiting = false;
        Set<SelectionKey> keys = nioEventLoopAdapter.getSelector().selectedKeys();
        try {
            for (SelectionKey key : keys) {
                if (key.isValid()) {
                    int readyOps = key.readyOps();
                    if ((readyOps & SelectionKey.OP_READ) != 0) {
                        nioEventLoopAdapter.read(key, this);
                    } else if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                        nioEventLoopAdapter.write(key);
                    } else {
                        key.cancel();
                    }
                } else {
                    key.cancel();
                }
            }
        } finally {
            keys.clear();
        }
    }

    private void handleInterruptedException(InterruptedException e) {
        logger.warn(getName() + " is interrupted");
        end();
    }

    private NioEventLoopAdapter nioEventLoopAdapter;

    public NioEventLoopAdapter getNioEventLoopAdapter() {
        return nioEventLoopAdapter;
    }

    public void register(AsyncConnection conn) {
        conn.getWritableChannel().setEventLoop(this); // 替换掉原来的
        nioEventLoopAdapter.register(conn);
        wakeUp();
    }

    @Override
    public NioEventLoop getDefaultNioEventLoopImpl() {
        return nioEventLoopAdapter;
    }

    @Override
    public void handleException(AsyncConnection conn, SocketChannel channel, Exception e) {
        if (conn != null) {
            conn.close();
        }
        closeChannel(channel);
    }

    private void initNioEventLoopAdapter(Map<String, String> config) {
        try {
            // 默认一直阻塞
            nioEventLoopAdapter = new NioEventLoopAdapter(config, "scheduler_loop_interval", 0);
        } catch (IOException e) {
            throw DbException.convert(e);
        }
    }
}
