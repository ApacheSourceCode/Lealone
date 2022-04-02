/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.storage.replication;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;

import org.lealone.db.DataBuffer;
import org.lealone.db.async.AsyncCallback;
import org.lealone.db.async.AsyncHandler;
import org.lealone.db.async.AsyncResult;
import org.lealone.db.async.Future;
import org.lealone.storage.StorageCommand;
import org.lealone.storage.page.LeafPageMovePlan;
import org.lealone.storage.page.PageKey;
import org.lealone.storage.replication.WriteResponseHandler.ReplicationResultHandler;
import org.lealone.storage.type.StorageDataType;

class ReplicationStorageCommand extends ReplicationCommand<ReplicaStorageCommand> implements StorageCommand {

    ReplicationStorageCommand(ReplicationSession session, ReplicaStorageCommand[] commands) {
        super(session, commands);
    }

    @Override
    public int getType() {
        return REPLICATION_STORAGE_COMMAND;
    }

    @Override
    public Future<Object> get(String mapName, Object key, StorageDataType keyType) {
        HashSet<ReplicaStorageCommand> seen = new HashSet<>();
        AsyncCallback<Object> ac = new AsyncCallback<>();
        executeGet(mapName, key, keyType, 1, seen, ac);
        return ac;
    }

    private void executeGet(String mapName, Object key, StorageDataType keyType, int tries,
            HashSet<ReplicaStorageCommand> seen, AsyncCallback<Object> ac) {
        AsyncHandler<AsyncResult<Object>> handler = ar -> {
            if (ar.isFailed() && tries < session.maxTries) {
                executeGet(mapName, key, keyType, tries + 1, seen, ac);
            } else {
                ac.setAsyncResult(ar);
            }
        };
        ReadResponseHandler<Object> readResponseHandler = new ReadResponseHandler<>(session, handler);

        // 随机选择1个节点处理读请求，如果读不到再试其他节点
        ReplicaStorageCommand c = getRandomNode(seen);
        c.get(mapName, key, keyType).onComplete(readResponseHandler);
    }

    @Override
    public Future<Object> put(String mapName, Object key, StorageDataType keyType, Object value,
            StorageDataType valueType, boolean raw, boolean addIfAbsent) {
        AsyncCallback<Object> ac = new AsyncCallback<>();
        DataBuffer k = DataBuffer.create();
        DataBuffer v = DataBuffer.create();
        ByteBuffer keyBuffer = k.write(keyType, key);
        ByteBuffer valueBuffer = v.write(valueType, value);
        executePut(mapName, keyBuffer, valueBuffer, raw, addIfAbsent, 1, ac);
        return ac;
    }

    private void executePut(String mapName, ByteBuffer key, ByteBuffer value, boolean raw, boolean addIfAbsent,
            int tries, AsyncCallback<Object> ac) {
        String rn = session.createReplicationName();
        AsyncHandler<AsyncResult<Object>> handler = ar -> {
            if (ar.isFailed() && tries < session.maxTries) {
                key.rewind();
                value.rewind();
                executePut(mapName, key, value, raw, addIfAbsent, tries + 1, ac);
            } else {
                ac.setAsyncResult(ar);
            }
        };
        WriteResponseHandler<Object> writeResponseHandler = new WriteResponseHandler<>(session, commands, handler);

        for (int i = 0; i < session.n; i++) {
            ReplicaStorageCommand c = commands[i];
            c.executeReplicaPut(rn, mapName, key.slice(), value.slice(), raw, addIfAbsent)
                    .onComplete(writeResponseHandler);
        }
    }

    @Override
    public Future<Object> append(String mapName, Object value, StorageDataType valueType) {
        AsyncCallback<Object> ac = new AsyncCallback<>();
        DataBuffer v = DataBuffer.create();
        ByteBuffer valueBuffer = v.write(valueType, value);
        executeAppend(mapName, valueBuffer, 1, ac);
        return ac;
    }

    private void executeAppend(String mapName, ByteBuffer value, int tries, AsyncCallback<Object> ac) {
        String rn = session.createReplicationName();
        AsyncHandler<AsyncResult<Object>> handler = ar -> {
            if (ar.isFailed() && tries < session.maxTries) {
                value.rewind();
                executeAppend(mapName, value, tries + 1, ac);
            } else {
                ac.setAsyncResult(ar);
            }
        };
        WriteResponseHandler<Object> writeResponseHandler = new WriteResponseHandler<>(session, commands, handler);

        for (int i = 0; i < session.n; i++) {
            commands[i].executeReplicaAppend(rn, mapName, value.slice()).onComplete(writeResponseHandler);
        }
    }

    @Override
    public Future<Boolean> replace(String mapName, Object key, StorageDataType keyType, Object oldValue,
            Object newValue, StorageDataType valueType) {
        AsyncCallback<Boolean> ac = new AsyncCallback<>();
        DataBuffer k = DataBuffer.create();
        DataBuffer v1 = DataBuffer.create();
        DataBuffer v2 = DataBuffer.create();
        ByteBuffer keyBuffer = k.write(keyType, key);
        ByteBuffer valueBuffer1 = v1.write(valueType, oldValue);
        ByteBuffer valueBuffer2 = v2.write(valueType, newValue);
        executeReplace(mapName, keyBuffer, valueBuffer1, valueBuffer2, 1, ac);
        return ac;
    }

    private void executeReplace(String mapName, ByteBuffer key, ByteBuffer oldValue, ByteBuffer newValue, int tries,
            AsyncCallback<Boolean> ac) {
        String rn = session.createReplicationName();
        AsyncHandler<AsyncResult<Boolean>> handler = ar -> {
            if (ar.isFailed() && tries < session.maxTries) {
                key.rewind();
                oldValue.rewind();
                newValue.rewind();
                executeReplace(mapName, key, oldValue, newValue, tries + 1, ac);
            } else {
                ac.setAsyncResult(ar);
            }
        };
        WriteResponseHandler<Boolean> writeResponseHandler = new WriteResponseHandler<>(session, commands, handler);

        for (int i = 0; i < session.n; i++) {
            ReplicaStorageCommand c = commands[i];
            c.executeReplicaReplace(rn, mapName, key.slice(), oldValue.slice(), newValue.slice())
                    .onComplete(writeResponseHandler);
        }
    }

    @Override
    public Future<Object> remove(String mapName, Object key, StorageDataType keyType) {
        AsyncCallback<Object> ac = new AsyncCallback<>();
        DataBuffer k = DataBuffer.create();
        ByteBuffer keyBuffer = k.write(keyType, key);
        executeRemove(mapName, keyBuffer, 1, ac);
        return ac;
    }

    private void executeRemove(String mapName, ByteBuffer key, int tries, AsyncCallback<Object> ac) {
        String rn = session.createReplicationName();
        AsyncHandler<AsyncResult<Object>> handler = ar -> {
            if (ar.isFailed() && tries < session.maxTries) {
                key.rewind();
                executeRemove(mapName, key, tries + 1, ac);
            } else {
                ac.setAsyncResult(ar);
            }
        };
        WriteResponseHandler<Object> writeResponseHandler = new WriteResponseHandler<>(session, commands, handler);

        for (int i = 0; i < session.n; i++) {
            ReplicaStorageCommand c = commands[i];
            c.executeReplicaRemove(rn, mapName, key.slice()).onComplete(writeResponseHandler);
        }
    }

    @Override
    public Future<LeafPageMovePlan> prepareMoveLeafPage(String mapName, LeafPageMovePlan leafPageMovePlan) {
        AsyncCallback<LeafPageMovePlan> ac = new AsyncCallback<>();
        AsyncHandler<AsyncResult<LeafPageMovePlan>> handler = ar -> {
            ac.setAsyncResult(ar);
        };
        prepareMoveLeafPage(mapName, leafPageMovePlan, 3, handler);
        return ac;
    }

    private void prepareMoveLeafPage(String mapName, LeafPageMovePlan leafPageMovePlan, int tries,
            AsyncHandler<AsyncResult<LeafPageMovePlan>> finalResultHandler) {
        int n = session.n;
        ReplicationResultHandler<LeafPageMovePlan> replicationResultHandler = results -> {
            LeafPageMovePlan plan = getValidPlan(results, n);
            if (plan == null && tries - 1 > 0) {
                leafPageMovePlan.incrementIndex();
                prepareMoveLeafPage(mapName, leafPageMovePlan, tries + 1, finalResultHandler);
            }
            return plan;
        };
        WriteResponseHandler<LeafPageMovePlan> writeResponseHandler = new WriteResponseHandler<>(session, commands,
                finalResultHandler, replicationResultHandler);

        for (int i = 0; i < n; i++) {
            commands[i].prepareMoveLeafPage(mapName, leafPageMovePlan).onComplete(writeResponseHandler);
        }
    }

    private LeafPageMovePlan getValidPlan(List<LeafPageMovePlan> plans, int n) {
        int w = n / 2 + 1;
        LeafPageMovePlan validPlan = null;

        // 1. 先看看是否有满足>=w的
        HashMap<String, ArrayList<LeafPageMovePlan>> groupPlans = new HashMap<>(1);
        for (LeafPageMovePlan p : plans) {
            ArrayList<LeafPageMovePlan> group = groupPlans.get(p.moverHostId);
            if (group == null) {
                group = new ArrayList<>(n);
                groupPlans.put(p.moverHostId, group);
            }
            group.add(p);
        }
        for (Entry<String, ArrayList<LeafPageMovePlan>> e : groupPlans.entrySet()) {
            ArrayList<LeafPageMovePlan> group = e.getValue();
            if (group.size() >= w) {
                validPlan = group.get(0);
                break;
            }
        }
        // 2. 如果没有，那就排序取moverHostId最大的那个
        if (validPlan == null && plans.size() >= w) {
            int index = 0;
            String moverHostId = plans.get(0).moverHostId;
            for (int i = 1; i < plans.size(); i++) {
                if (plans.get(i).moverHostId.compareTo(moverHostId) > 0) {
                    moverHostId = plans.get(i).moverHostId;
                    index = i;
                }
            }
            validPlan = plans.get(index);
        }
        return validPlan;
    }

    @Override
    public void moveLeafPage(String mapName, PageKey pageKey, ByteBuffer page, boolean addPage) {
        for (int i = 0, n = session.n; i < n; i++) {
            commands[i].moveLeafPage(mapName, pageKey, page.slice(), addPage);
        }
    }

    @Override
    public void replicatePages(String dbName, String storageName, ByteBuffer pages) {
        for (int i = 0, n = session.n; i < n; i++) {
            commands[i].replicatePages(dbName, storageName, pages.slice());
        }
    }

    @Override
    public void removeLeafPage(String mapName, PageKey pageKey) {
        for (int i = 0, n = session.n; i < n; i++) {
            commands[i].removeLeafPage(mapName, pageKey);
        }
    }

    @Override
    public Future<ByteBuffer> readRemotePage(String mapName, PageKey pageKey) {
        return commands[0].readRemotePage(mapName, pageKey);
    }
}
