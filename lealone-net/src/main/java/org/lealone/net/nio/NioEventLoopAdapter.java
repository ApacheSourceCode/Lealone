/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.net.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.logging.Logger;
import org.lealone.common.logging.LoggerFactory;
import org.lealone.common.util.DateTimeUtils;
import org.lealone.db.DataBuffer;
import org.lealone.net.AsyncConnection;

public class NioEventLoopAdapter implements NioEventLoop {

    private static final Logger logger = LoggerFactory.getLogger(NioEventLoopAdapter.class);

    private final ConcurrentHashMap<SocketChannel, ConcurrentLinkedQueue<NioBuffer>> channels = new ConcurrentHashMap<>();

    private final AtomicBoolean selecting = new AtomicBoolean(false);
    private Selector selector;
    private final long loopInterval;

    public NioEventLoopAdapter(Map<String, String> config, String loopIntervalKey, long loopIntervalDefaultValue)
            throws IOException {
        loopInterval = DateTimeUtils.getLoopInterval(config, loopIntervalKey, loopIntervalDefaultValue);
        selector = Selector.open();
    }

    @Override
    public NioEventLoop getDefaultNioEventLoopImpl() {
        return this;
    }

    @Override
    public Selector getSelector() {
        return selector;
    }

    @Override
    public void select() throws IOException {
        select(loopInterval);
    }

    @Override
    public void select(long timeout) throws IOException {
        // tryRegisterWriteOperation(selector);
        if (selecting.compareAndSet(false, true)) {
            selector.select(timeout);
            selecting.set(false);
        }
    }

    @Override
    public void register(SocketChannel channel, int ops, Object att) throws ClosedChannelException {
        // 当nio-event-loop线程执行selector.select被阻塞时，代码内部依然会占用publicKeys锁，
        // 而另一个线程执行channel.register时，内部也会去要publicKeys锁，从而导致也被阻塞，
        // 所以下面这段代码的用处是:
        // 只要发现nio-event-loop线程正在进行select，那么就唤醒它，并释放publicKeys锁。
        while (true) {
            if (selecting.compareAndSet(false, true)) {
                channel.register(selector, SelectionKey.OP_CONNECT, att);
                selecting.set(false);
                selector.wakeup();
                break;
            } else {
                selector.wakeup();
            }
        }
    }

    @Override
    public void wakeup() {
        if (selecting.compareAndSet(true, false)) {
            selector.wakeup();
        }
    }

    @Override
    public void addSocketChannel(SocketChannel channel) {
        channels.putIfAbsent(channel, new ConcurrentLinkedQueue<>());
    }

    @Override
    public void addNioBuffer(SocketChannel channel, NioBuffer nioBuffer) {
        ConcurrentLinkedQueue<NioBuffer> queue = channels.get(channel);
        if (queue != null) {
            SelectionKey key = channel.keyFor(getSelector());
            // 还没有注册
            if (key == null) {
                queue.add(nioBuffer);
                wakeup();
                return;
            }
            // 当队列不为空时，队首的NioBuffer可能没写完，此时不能写新的NioBuffer
            if (queue.isEmpty() && key.isValid()) { // && key.isWritable()) {
                if (write(key, channel, nioBuffer)) {
                    deregisterWrite(key);
                    return;
                }
            }
            queue.add(nioBuffer);
            registerWrite(key);
            wakeup();
        }
    }

    private void registerWrite(SelectionKey key) {
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
    }

    private void deregisterWrite(SelectionKey key) {
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }

    @Override
    public void tryRegisterWriteOperation(Selector selector) {
        for (Entry<SocketChannel, ConcurrentLinkedQueue<NioBuffer>> entry : channels.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                for (SelectionKey key : selector.keys()) {
                    if (key.channel() == entry.getKey() && key.isValid()) {
                        registerWrite(key);
                        break;
                    }
                }
            }
        }
    }

    private long totalReadBytes;
    private long totalWrittenBytes;
    private final boolean isDebugEnabled = logger.isDebugEnabled();

    @Override
    public void read(SelectionKey key, NioEventLoop nioEventLoop) {
        SocketChannel channel = (SocketChannel) key.channel();
        NioAttachment attachment = (NioAttachment) key.attachment();
        AsyncConnection conn = attachment.conn;
        DataBuffer dataBuffer = attachment.dataBuffer;
        try {
            while (true) {
                // 每次循环重新取一次，一些实现会返回不同的Buffer
                ByteBuffer packetLengthByteBuffer = conn.getPacketLengthByteBuffer();
                int packetLengthByteBufferCapacity = packetLengthByteBuffer.capacity();

                if (attachment.state == 0) {
                    boolean ok = read(attachment, channel, packetLengthByteBuffer, packetLengthByteBufferCapacity);
                    if (ok) {
                        attachment.state = 1;
                    } else {
                        break;
                    }
                }
                if (attachment.state == 1) {
                    int packetLength = conn.getPacketLength();
                    if (dataBuffer == null) {
                        dataBuffer = DataBuffer.getOrCreate(packetLength);
                        dataBuffer.limit(packetLength); // 返回的DatBuffer的Capacity可能大于packetLength，所以设置一下limit，不会多读
                    }
                    ByteBuffer buffer = dataBuffer.getBuffer();
                    boolean ok = read(attachment, channel, buffer, packetLength);
                    if (ok) {
                        packetLengthByteBuffer.clear();
                        attachment.state = 0;
                        attachment.dataBuffer = null;
                        NioBuffer nioBuffer = new NioBuffer(dataBuffer, true); // 支持快速回收
                        dataBuffer = null;
                        conn.handle(nioBuffer);
                    } else {
                        packetLengthByteBuffer.flip(); // 下次可以重新计算packetLength
                        attachment.dataBuffer = dataBuffer;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            nioEventLoop.handleException(conn, channel, e);
        }
    }

    private boolean read(NioAttachment attachment, SocketChannel channel, ByteBuffer buffer, int length)
            throws Exception {
        int readBytes = channel.read(buffer);
        if (readBytes > 0) {
            if (isDebugEnabled) {
                totalReadBytes += readBytes;
                logger.debug(("total read bytes: " + totalReadBytes));
            }
            attachment.endOfStreamCount = 0;
        } else {
            // 客户端非正常关闭时，可能会触发JDK的bug，导致run方法死循环，selector.select不会阻塞
            // netty框架在下面这个方法的代码中有自己的不同解决方案
            // io.netty.channel.nio.NioEventLoop.processSelectedKey
            if (readBytes < 0) {
                attachment.endOfStreamCount++;
                if (attachment.endOfStreamCount > 3) {
                    closeChannel(channel);
                }
            }
        }
        if (length == buffer.position()) {
            buffer.flip();
            return true;
        }
        return false;
    }

    @Override
    public void write(SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();
        Queue<NioBuffer> queue = channels.get(channel);
        for (NioBuffer nioBuffer : queue) {
            if (write(key, channel, nioBuffer))
                queue.remove(nioBuffer);
        }
        // 还是要检测key是否是有效的，否则会抛CancelledKeyException
        if (queue.isEmpty() && key.isValid()) {
            deregisterWrite(key);
        }
    }

    private boolean write(SelectionKey key, SocketChannel channel, NioBuffer nioBuffer) {
        ByteBuffer buffer = nioBuffer.getByteBuffer();
        int remaining = buffer.remaining();
        try {
            // 一定要用while循环来写，否则会丢数据！
            while (remaining > 0) {
                int writtenBytes = channel.write(buffer);
                if (writtenBytes <= 0) {
                    if (key.isValid()) {
                        registerWrite(key);
                    }
                    return false; // 还没有写完
                }
                remaining -= writtenBytes;
                if (isDebugEnabled) {
                    totalWrittenBytes += writtenBytes;
                    logger.debug(("total written bytes: " + totalWrittenBytes));
                }
            }
        } catch (IOException e) {
            closeChannel(channel);
        }
        nioBuffer.recycle();
        return true;
    }

    @Override
    public void closeChannel(SocketChannel channel) {
        if (channel == null || !channels.containsKey(channel)) {
            return;
        }
        for (SelectionKey key : selector.keys()) {
            if (key.channel() == channel && key.isValid()) {
                key.cancel();
                break;
            }
        }
        channels.remove(channel);
        NioNetServer.closeChannel(channel);
    }

    public void close() {
        try {
            Selector selector = this.selector;
            this.selector = null;
            selector.wakeup();
            selector.close();
        } catch (Exception e) {
        }
    }

    public void register(AsyncConnection conn) {
        NioAttachment attachment = new NioAttachment();
        attachment.conn = conn;
        SocketChannel channel = conn.getWritableChannel().getSocketChannel();
        addSocketChannel(channel);
        try {
            channel.register(getSelector(), SelectionKey.OP_READ, attachment);
        } catch (ClosedChannelException e) {
            throw DbException.convert(e);
        }
    }
}
