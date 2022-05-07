/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.db.session;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.SystemPropertyUtils;
import org.lealone.db.ConnectionInfo;
import org.lealone.db.ConnectionSetting;
import org.lealone.db.async.Future;
import org.lealone.net.NetNodeManagerHolder;
import org.lealone.transaction.Transaction;

public class SessionPool {

    private static final int QUEUE_SIZE = SystemPropertyUtils.getInt("lealone.session.pool.queue.size", 3);

    // key是访问数据库的JDBC URL
    private static final ConcurrentHashMap<String, ConcurrentLinkedQueue<Session>> pool = new ConcurrentHashMap<>();

    private static ConcurrentLinkedQueue<Session> getQueue(String url) {
        ConcurrentLinkedQueue<Session> queue = pool.get(url);
        if (queue == null) {
            // 避免多个线程生成不同的ConcurrentLinkedQueue实例
            synchronized (SessionPool.class) {
                queue = pool.get(url);
                if (queue == null) {
                    queue = new ConcurrentLinkedQueue<>();
                    pool.put(url, queue);
                }
            }
        }
        return queue;
    }

    private static Session getSessionFromCache(String url, boolean remote) {
        Session session = remote ? getQueue(url).poll() : null; // 在本地创建session时不用从缓存队列中找
        if (session == null || session.isClosed())
            return null;
        return session;
    }

    private static Future<Session> createSessionAsync(ServerSession originalSession, String url, boolean remote) {
        ConnectionInfo oldCi = originalSession.getConnectionInfo();
        // 未来新加的代码如果忘记设置这个字段，出问题时方便查找原因
        if (oldCi == null) {
            throw DbException.getInternalError();
        }

        ConnectionInfo ci = new ConnectionInfo(url, oldCi.getProperties());
        ci.setProperty(ConnectionSetting.IS_ROOT, "false");
        ci.setUserName(oldCi.getUserName());
        ci.setUserPasswordHash(oldCi.getUserPasswordHash());
        ci.setFilePasswordHash(oldCi.getFilePasswordHash());
        ci.setFileEncryptionKey(oldCi.getFileEncryptionKey());
        ci.setRemote(remote);
        ci.setNetworkTimeout(NetNodeManagerHolder.get().getRpcTimeout());
        // 因为已经精确知道要连哪个节点了，不用考虑运行模式，所以用false
        return ci.getSessionFactory().createSession(ci, false);
    }

    public static Session getSessionSync(ServerSession originalSession, String url, boolean remote) {
        Session session = getSessionFromCache(url, remote);
        if (session != null)
            return session;

        // 这里没有直接使用Future.get，是因为在分布式场景下两个节点互联时，
        // 若是调用createSessionAsync的线程和解析session初始化包时分配的线程是同一个，会导致死锁。
        Transaction.Listener listener = Transaction.getTransactionListener();
        listener.beforeOperation();
        AtomicReference<Session> sessionRef = new AtomicReference<>();
        createSessionAsync(originalSession, url, remote).onComplete(ar -> {
            if (ar.isSucceeded()) {
                sessionRef.set(ar.getResult());
                listener.operationComplete();
            } else {
                listener.setException(ar.getCause());
                listener.operationUndo();
            }
        });
        listener.await();
        return sessionRef.get();
    }

    public static Future<Session> getSessionAsync(ServerSession originalSession, String url, boolean remote) {
        Session session = getSessionFromCache(url, remote);
        if (session != null)
            return Future.succeededFuture(session);
        return createSessionAsync(originalSession, url, remote);
    }

    public static void release(Session session) {
        if (session == null || session.isClosed())
            return;

        if (session instanceof ServerSession) {
            session.close();
            return;
        }

        ConcurrentLinkedQueue<Session> queue = getQueue(session.getURL());
        if (queue.size() > QUEUE_SIZE)
            session.close();
        else
            queue.offer(session);
    }
}
