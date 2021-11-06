/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.net.nio;

import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;

import org.lealone.common.concurrent.ConcurrentUtils;
import org.lealone.common.logging.Logger;
import org.lealone.common.logging.LoggerFactory;
import org.lealone.net.AsyncConnection;
import org.lealone.net.NetServerBase;

//TODO 1.支持SSL 2.支持配置参数
public class NioEventLoopNetServer extends NetServerBase implements NioEventLoop {

    private static final Logger logger = LoggerFactory.getLogger(NioEventLoopNetServer.class);
    private ServerSocketChannel serverChannel;
    private NioEventLoopAdapter nioEventLoopAdapter;

    @Override
    public synchronized void start() {
        if (isStarted())
            return;
        logger.info("Starting nio net server");
        try {
            nioEventLoopAdapter = new NioEventLoopAdapter(config, "server_nio_event_loop_interval", 1000); // 默认1秒
            serverChannel = ServerSocketChannel.open();
            serverChannel.socket().bind(new InetSocketAddress(getHost(), getPort()));
            serverChannel.configureBlocking(false);
            serverChannel.register(nioEventLoopAdapter.getSelector(), SelectionKey.OP_ACCEPT);
            super.start();
            String name = "ServerNioEventLoopService-" + getPort();
            if (runInMainThread()) {
                Thread t = Thread.currentThread();
                if (t.getName().equals("main"))
                    t.setName(name);
            } else {
                ConcurrentUtils.submitTask(name, () -> {
                    NioEventLoopNetServer.this.run();
                });
            }
        } catch (Exception e) {
            checkBindException(e, "Failed to start nio net server");
        }
    }

    @Override
    public Runnable getRunnable() {
        return () -> {
            NioEventLoopNetServer.this.run();
        };
    }

    private void tryRegisterWriteOperation() {
        Selector selector = nioEventLoopAdapter.getSelector();
        nioEventLoopAdapter.tryRegisterWriteOperation(selector);
    }

    private void run() {
        for (;;) {
            try {
                tryRegisterWriteOperation();
                nioEventLoopAdapter.select();
                if (isStopped())
                    break;
                Set<SelectionKey> keys = nioEventLoopAdapter.getSelector().selectedKeys();
                try {
                    for (SelectionKey key : keys) {
                        if (key.isValid()) {
                            int readyOps = key.readyOps();
                            if ((readyOps & SelectionKey.OP_READ) != 0) {
                                read(key, this);
                            } else if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                                write(key);
                            } else if ((readyOps & SelectionKey.OP_ACCEPT) != 0) {
                                accept();
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
                if (isStopped())
                    break;
            } catch (Throwable e) {
                logger.warn(Thread.currentThread().getName() + " run exception", e);
            }
        }
    }

    private void accept() {
        SocketChannel channel = null;
        AsyncConnection conn = null;
        try {
            channel = serverChannel.accept();
            channel.configureBlocking(false);
            nioEventLoopAdapter.addSocketChannel(channel);
            NioWritableChannel writableChannel = new NioWritableChannel(channel, this);
            conn = createConnection(writableChannel, true);

            NioAttachment attachment = new NioAttachment();
            attachment.conn = conn;
            channel.register(nioEventLoopAdapter.getSelector(), SelectionKey.OP_READ, attachment);
        } catch (Throwable e) {
            if (conn != null) {
                removeConnection(conn);
            }
            closeChannel(channel);
            logger.warn(getName() + " failed to accept", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (isStopped())
            return;
        logger.info("Stopping nio net server");
        super.stop();
        nioEventLoopAdapter.close();
        if (serverChannel != null) {
            try {
                serverChannel.close();
                serverChannel = null;
            } catch (Throwable e) {
            }
        }
    }

    @Override
    public NioEventLoop getDefaultNioEventLoopImpl() {
        return nioEventLoopAdapter;
    }

    @Override
    public boolean runInMainThread() {
        return runInMainThread;
    }

    @Override
    public void handleException(AsyncConnection conn, SocketChannel channel, Exception e) {
        if (conn != null) {
            removeConnection(conn);
        }
        closeChannel(channel);
    }
}
