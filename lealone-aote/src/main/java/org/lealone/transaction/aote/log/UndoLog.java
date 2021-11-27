/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.transaction.aote.log;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

import org.lealone.db.DataBuffer;
import org.lealone.transaction.aote.AMTransactionEngine;
import org.lealone.transaction.aote.tvalue.TransactionalValue;

public class UndoLog {

    private int logId;
    private final LinkedList<UndoLogRecord> undoLogRecords = new LinkedList<>();

    public int getLogId() {
        return logId;
    }

    public LinkedList<UndoLogRecord> getUndoLogRecords() {
        return undoLogRecords;
    }

    public boolean isEmpty() {
        return undoLogRecords.isEmpty();
    }

    public boolean isNotEmpty() {
        return !undoLogRecords.isEmpty();
    }

    public UndoLogRecord getLast() {
        return undoLogRecords.getLast();
    }

    public int size() {
        return undoLogRecords.size();
    }

    public UndoLogRecord add(String mapName, Object key, TransactionalValue oldValue, TransactionalValue newValue,
            boolean isForUpdate) {
        UndoLogRecord r = new UndoLogRecord(mapName, key, oldValue, newValue, isForUpdate);
        undoLogRecords.add(r);
        logId++;
        return r;
    }

    public UndoLogRecord add(String mapName, Object key, TransactionalValue oldValue, TransactionalValue newValue) {
        return add(mapName, key, oldValue, newValue, false);
    }

    public void undo() {
        undoLogRecords.removeLast();
        --logId;
    }

    public void commit(AMTransactionEngine transactionEngine, long tid) {
        for (UndoLogRecord r : undoLogRecords) {
            r.commit(transactionEngine, tid);
        }
    }

    public void rollbackTo(AMTransactionEngine transactionEngine, int toLogId) {
        while (logId > toLogId) {
            UndoLogRecord r = undoLogRecords.removeLast();
            r.rollback(transactionEngine);
            --logId;
        }
    }

    public void setRetryReplicationNames(List<String> retryReplicationNames, int toLogId) {
        int index = logId - 1;
        for (UndoLogRecord r : undoLogRecords) {
            if (index-- < toLogId)
                break;
            r.setRetryReplicationNames(retryReplicationNames);
        }
    }

    private static int lastCapacity = 1024;

    // 将当前一系列的事务操作日志转换成单条RedoLogRecord
    public ByteBuffer toRedoLogRecordBuffer(AMTransactionEngine transactionEngine) {
        if (undoLogRecords.isEmpty())
            return null;
        DataBuffer writeBuffer = DataBuffer.create(lastCapacity);
        for (UndoLogRecord r : undoLogRecords) {
            r.writeForRedo(writeBuffer, transactionEngine);
        }
        lastCapacity = writeBuffer.position();
        if (lastCapacity > 1024)
            lastCapacity = 1024;
        return writeBuffer.getAndFlipBuffer();
    }
}
