/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.storage.aose.btree;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.DataUtils;
import org.lealone.common.util.StringUtils;
import org.lealone.db.DataBuffer;
import org.lealone.db.IDatabase;
import org.lealone.db.RunMode;
import org.lealone.db.async.AsyncHandler;
import org.lealone.db.async.AsyncResult;
import org.lealone.db.async.Future;
import org.lealone.db.session.Session;
import org.lealone.db.value.ValueLong;
import org.lealone.db.value.ValueNull;
import org.lealone.net.NetNode;
import org.lealone.storage.CursorParameters;
import org.lealone.storage.StorageCommand;
import org.lealone.storage.StorageMapBase;
import org.lealone.storage.StorageMapCursor;
import org.lealone.storage.aose.AOStorage;
import org.lealone.storage.aose.btree.chunk.Chunk;
import org.lealone.storage.aose.btree.page.LeafPage;
import org.lealone.storage.aose.btree.page.Page;
import org.lealone.storage.aose.btree.page.PageKeyCursor;
import org.lealone.storage.aose.btree.page.PageOperations.Append;
import org.lealone.storage.aose.btree.page.PageOperations.Put;
import org.lealone.storage.aose.btree.page.PageOperations.PutIfAbsent;
import org.lealone.storage.aose.btree.page.PageOperations.Remove;
import org.lealone.storage.aose.btree.page.PageOperations.Replace;
import org.lealone.storage.aose.btree.page.PageOperations.RunnableOperation;
import org.lealone.storage.aose.btree.page.PageOperations.SingleWrite;
import org.lealone.storage.aose.btree.page.PageReference;
import org.lealone.storage.aose.btree.page.PageStorageMode;
import org.lealone.storage.aose.btree.page.RemotePage;
import org.lealone.storage.page.LeafPageMovePlan;
import org.lealone.storage.page.PageKey;
import org.lealone.storage.page.PageOperation;
import org.lealone.storage.page.PageOperation.PageOperationResult;
import org.lealone.storage.page.PageOperationHandler;
import org.lealone.storage.page.PageOperationHandlerFactory;
import org.lealone.storage.type.StorageDataType;

/**
 * 支持同步和异步风格的BTree.
 * 
 * <p>
 * 对于写操作，使用同步风格的API时会阻塞线程，异步风格的API不阻塞线程.
 * <p>
 * 对于读操作，不阻塞线程，允许多线程对BTree进行读取操作.
 * 
 * @param <K> the key class
 * @param <V> the value class
 */
public class BTreeMap<K, V> extends StorageMapBase<K, V> {

    // 只允许通过成员方法访问这个特殊的字段
    private final AtomicLong size = new AtomicLong(0);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final boolean readOnly;
    private final boolean inMemory;
    private final Map<String, Object> config;
    private final BTreeStorage btreeStorage;
    private final PageOperationHandlerFactory pohFactory;
    private PageStorageMode pageStorageMode = PageStorageMode.ROW_STORAGE;

    private class RootPageReference extends PageReference {
        @Override
        public void replacePage(Page page) {
            super.replacePage(page);
            setRootRef(page); // 当要替换page时也设置root page相关信息
        }
    }

    private final RootPageReference rootRef = new RootPageReference();
    // btree的root page，最开始是一个leaf page，随时都会指向新的page
    private Page root;

    public BTreeMap(String name, StorageDataType keyType, StorageDataType valueType, Map<String, Object> config,
            AOStorage aoStorage) {
        super(name, keyType, valueType, aoStorage);
        DataUtils.checkNotNull(config, "config");
        // 只要包含就为true
        readOnly = config.containsKey("readOnly");
        inMemory = config.containsKey("inMemory");

        this.config = config;
        this.pohFactory = (PageOperationHandlerFactory) config.get("pohFactory");
        Object mode = config.get("pageStorageMode");
        if (mode != null) {
            pageStorageMode = PageStorageMode.valueOf(mode.toString());
        }
        if (config.containsKey("isShardingMode"))
            isShardingMode = Boolean.parseBoolean(config.get("isShardingMode").toString());
        else
            isShardingMode = false;
        db = (IDatabase) config.get("db");

        btreeStorage = new BTreeStorage(this);
        Chunk lastChunk = btreeStorage.getLastChunk();
        if (lastChunk != null) {
            size.set(lastChunk.mapSize);
            Page root = btreeStorage.readPage(lastChunk.rootPagePos);
            // 提前设置，如果root page是node类型，子page就能在Page.getChildPage中找到ParentRef
            setRootRef(root);
            setMaxKey(lastKey());
        } else {
            if (isShardingMode) {
                String initReplicationNodes = (String) config.get("initReplicationNodes");
                DataUtils.checkNotNull(initReplicationNodes, "initReplicationNodes");
                String[] replicationNodes = StringUtils.arraySplit(initReplicationNodes, '&');
                if (containsLocalNode(replicationNodes)) {
                    root = LeafPage.createEmpty(this);
                } else {
                    root = new RemotePage(this);
                }
                root.setReplicationHostIds(Arrays.asList(replicationNodes));
                // 强制把replicationHostIds持久化
                btreeStorage.forceSave();
            } else {
                root = LeafPage.createEmpty(this);
            }
            setRootRef(root);
        }
    }

    private boolean containsLocalNode(String[] replicationNodes) {
        for (String n : replicationNodes) {
            if (NetNode.isLocalTcpNode(n))
                return true;
        }
        return false;
    }

    private void setRootRef(Page root) {
        if (this.root != root) {
            this.root = root;
        }
        if (rootRef.getPage() != root) {
            if (root.getRef() != rootRef) {
                root.setRef(rootRef);
                rootRef.replacePage(root);
            }
            if (root.isNode()) {
                for (PageReference ref : root.getChildren()) {
                    Page p = ref.getPage();
                    if (p != null && p.getParentRef() != rootRef)
                        p.setParentRef(rootRef);
                }
            }
        }
    }

    public Page getRootPage() {
        return root;
    }

    public void newRoot(Page newRoot) {
        setRootRef(newRoot);
    }

    private void acquireSharedLock() {
        lock.readLock().lock();
    }

    private void releaseSharedLock() {
        lock.readLock().unlock();
    }

    private void acquireExclusiveLock() {
        lock.writeLock().lock();
    }

    private void releaseExclusiveLock() {
        lock.writeLock().unlock();
    }

    public PageOperationHandlerFactory getPohFactory() {
        return pohFactory;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public Object getConfig(String key) {
        return config.get(key);
    }

    public BTreeStorage getBTreeStorage() {
        return btreeStorage;
    }

    public PageStorageMode getPageStorageMode() {
        return pageStorageMode;
    }

    public void setPageStorageMode(PageStorageMode pageStorageMode) {
        this.pageStorageMode = pageStorageMode;
    }

    @Override
    public V get(K key) {
        return binarySearch(key, true);
    }

    public V get(K key, boolean allColumns) {
        return binarySearch(key, allColumns);
    }

    public V get(K key, int columnIndex) {
        return binarySearch(key, new int[] { columnIndex });
    }

    @Override
    public V get(K key, int[] columnIndexes) {
        return binarySearch(key, columnIndexes);
    }

    // test only
    public int getLevel(K key) {
        int level = 1;
        Page p = root.gotoLeafPage(key);
        PageReference parentRef = p.getParentRef();
        while (parentRef != null) {
            level++;
            parentRef = parentRef.getPage().getParentRef();
        }
        return level;
    }

    @SuppressWarnings("unchecked")
    private V binarySearch(Object key, boolean allColumns) {
        Page p = root.gotoLeafPage(key);
        int index = p.binarySearch(key);
        return index >= 0 ? (V) p.getValue(index, allColumns) : null;
    }

    @SuppressWarnings("unchecked")
    private V binarySearch(Object key, int[] columnIndexes) {
        Page p = root.gotoLeafPage(key);
        int index = p.binarySearch(key);
        return index >= 0 ? (V) p.getValue(index, columnIndexes) : null;
    }

    @Override
    public K firstKey() {
        return getFirstLast(true);
    }

    @Override
    public K lastKey() {
        return getFirstLast(false);
    }

    /**
     * Get the first (lowest) or last (largest) key.
     * 
     * @param first whether to retrieve the first key
     * @return the key, or null if the map is empty
     */
    @SuppressWarnings("unchecked")
    private K getFirstLast(boolean first) {
        if (isEmpty()) {
            return null;
        }
        Page p = root;
        while (true) {
            if (p.isLeaf()) {
                return (K) p.getKey(first ? 0 : p.getKeyCount() - 1);
            }
            p = p.getChildPage(first ? 0 : getChildPageCount(p) - 1);
        }
    }

    @Override
    public K lowerKey(K key) {
        return getMinMax(key, true, true);
    }

    @Override
    public K floorKey(K key) {
        return getMinMax(key, true, false);
    }

    @Override
    public K higherKey(K key) {
        return getMinMax(key, false, true);
    }

    @Override
    public K ceilingKey(K key) {
        return getMinMax(key, false, false);
    }

    /**
     * Get the smallest or largest key using the given bounds.
     * 
     * @param key the key
     * @param min whether to retrieve the smallest key
     * @param excluding if the given upper/lower bound is exclusive
     * @return the key, or null if no such key exists
     */
    private K getMinMax(K key, boolean min, boolean excluding) {
        return getMinMax(root, key, min, excluding);
    }

    @SuppressWarnings("unchecked")
    private K getMinMax(Page p, K key, boolean min, boolean excluding) {
        if (p.isLeaf()) {
            int x = p.binarySearch(key);
            if (x < 0) {
                x = -x - (min ? 2 : 1);
            } else if (excluding) {
                x += min ? -1 : 1;
            }
            if (x < 0 || x >= p.getKeyCount()) {
                return null;
            }
            return (K) p.getKey(x);
        }
        int x = p.getPageIndex(key);
        while (true) {
            if (x < 0 || x >= getChildPageCount(p)) {
                return null;
            }
            K k = getMinMax(p.getChildPage(x), key, min, excluding);
            if (k != null) {
                return k;
            }
            x += min ? -1 : 1;
        }
    }

    @Override
    public boolean areValuesEqual(Object a, Object b) {
        if (a == b) {
            return true;
        } else if (a == null || b == null) {
            return false;
        }
        return valueType.compare(a, b) == 0;
    }

    @Override
    public long size() {
        return size.get();
    }

    public void incrementSize() {
        size.incrementAndGet();
    }

    public void decrementSize() {
        size.decrementAndGet();
    }

    @Override
    public boolean containsKey(K key) {
        return get(key) != null;
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean isInMemory() {
        return inMemory;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public StorageMapCursor<K, V> cursor(K from) {
        return cursor(CursorParameters.create(from));
    }

    @Override
    public StorageMapCursor<K, V> cursor(CursorParameters<K> parameters) {
        if (parameters.pageKeys == null)
            return new BTreeCursor<>(this, parameters);
        else
            return new PageKeyCursor<>(this, parameters);
    }

    @Override
    public void clear() {
        checkWrite();
        try {
            acquireExclusiveLock();

            List<String> replicationHostIds = root.getReplicationHostIds();
            root.removeAllRecursive();
            size.set(0);
            maxKey.set(0);
            newRoot(LeafPage.createEmpty(this));
            root.setReplicationHostIds(replicationHostIds);
        } finally {
            releaseExclusiveLock();
        }
    }

    @Override
    public void remove() {
        try {
            acquireExclusiveLock();

            btreeStorage.remove();
            closeMap();
        } finally {
            releaseExclusiveLock();
        }
    }

    @Override
    public boolean isClosed() {
        return btreeStorage.isClosed();
    }

    @Override
    public void close() {
        try {
            acquireExclusiveLock();

            closeMap();
            btreeStorage.close();
        } finally {
            releaseExclusiveLock();
        }
    }

    private void closeMap() {
        storage.closeMap(name);
    }

    @Override
    public void save() {
        try {
            acquireSharedLock(); // 用共享锁

            btreeStorage.save();
        } finally {
            releaseSharedLock();
        }
    }

    public int getChildPageCount(Page p) {
        return p.getRawChildPageCount();
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public String toString() {
        return name;
    }

    public void printPage() {
        printPage(true);
    }

    public void printPage(boolean readOffLinePage) {
        System.out.println(root.getPrettyPageInfo(readOffLinePage));
    }

    @Override
    public long getDiskSpaceUsed() {
        return btreeStorage.getDiskSpaceUsed();
    }

    @Override
    public long getMemorySpaceUsed() {
        return btreeStorage.getMemorySpaceUsed();
    }

    public Page gotoLeafPage(Object key) {
        return root.gotoLeafPage(key);
    }

    public Page gotoLeafPage(Object key, boolean markDirty) {
        return root.gotoLeafPage(key, markDirty);
    }

    // 如果map是只读的或者已经关闭了就不能再写了，并且不允许值为null
    private void checkWrite(V value) {
        DataUtils.checkNotNull(value, "value");
        checkWrite();
    }

    private void checkWrite() {
        if (btreeStorage.isClosed()) {
            throw DataUtils.newIllegalStateException(DataUtils.ERROR_CLOSED, "This map is closed");
        }
        if (readOnly) {
            throw DataUtils.newUnsupportedOperationException("This map is read-only");
        }
    }

    //////////////////// 以下是同步和异步API的实现 ////////////////////////////////

    @Override
    public void get(K key, AsyncHandler<AsyncResult<V>> handler) {
        V v = get(key);
        handler.handle(new AsyncResult<>(v));
    }

    @Override
    public V put(K key, V value) {
        return put0(key, value, null);
    }

    @Override
    public void put(K key, V value, AsyncHandler<AsyncResult<V>> handler) {
        put0(key, value, handler);
    }

    private V put0(K key, V value, AsyncHandler<AsyncResult<V>> handler) {
        checkWrite(value);
        Put<K, V, V> put = new Put<>(this, key, value, handler);
        return runPageOperation(put);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return putIfAbsent0(key, value, null);
    }

    @Override
    public void putIfAbsent(K key, V value, AsyncHandler<AsyncResult<V>> handler) {
        putIfAbsent0(key, value, handler);
    }

    private V putIfAbsent0(K key, V value, AsyncHandler<AsyncResult<V>> handler) {
        checkWrite(value);
        PutIfAbsent<K, V> putIfAbsent = new PutIfAbsent<>(this, key, value, handler);
        return runPageOperation(putIfAbsent);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        return replace0(key, oldValue, newValue, null);
    }

    @Override
    public void replace(K key, V oldValue, V newValue, AsyncHandler<AsyncResult<Boolean>> handler) {
        replace0(key, oldValue, newValue, handler);
    }

    private boolean replace0(K key, V oldValue, V newValue, AsyncHandler<AsyncResult<Boolean>> handler) {
        checkWrite(newValue);
        Replace<K, V> replace = new Replace<>(this, key, oldValue, newValue, handler);
        return runPageOperation(replace);
    }

    @Override
    public K append(V value) {
        return append0(value, null);
    }

    @Override
    public K append(V value, AsyncHandler<AsyncResult<K>> handler) {
        return append0(value, handler);
    }

    @SuppressWarnings("unchecked")
    private K append0(V value, AsyncHandler<AsyncResult<K>> handler) {
        checkWrite(value);
        // 先得到一个long类型的key
        K key = (K) ValueLong.get(maxKey.incrementAndGet());
        Append<K, V> append = new Append<>(this, key, value, handler);
        runPageOperation(append);
        return key;
    }

    @Override
    public V remove(K key) {
        return remove0(key, null);
    }

    @Override
    public void remove(K key, AsyncHandler<AsyncResult<V>> handler) {
        remove0(key, handler);
    }

    private V remove0(K key, AsyncHandler<AsyncResult<V>> handler) {
        checkWrite();
        Remove<K, V> remove = new Remove<>(this, key, handler);
        return runPageOperation(remove);
    }

    private <R> R runPageOperation(SingleWrite<?, ?, R> po) {
        PageOperationHandler poHandler = getPageOperationHandler(false);
        // 先快速试一次，如果不成功再用异步等待的方式
        if (po.run(poHandler) == PageOperationResult.SUCCEEDED)
            return po.getResult();
        poHandler = getPageOperationHandler(true);
        if (po.getResultHandler() == null) { // 同步
            PageOperation.Listener<R> listener = getPageOperationListener();
            po.setResultHandler(listener);
            poHandler.handlePageOperation(po);
            return listener.await();
        } else { // 异步
            poHandler.handlePageOperation(po);
            return null;
        }
    }

    // 如果当前线程不是PageOperationHandler，第一次运行时创建一个DummyPageOperationHandler
    // 第二次运行时需要加到现有线程池某个PageOperationHandler的队列中
    private PageOperationHandler getPageOperationHandler(boolean useThreadPool) {
        Object t = Thread.currentThread();
        if (t instanceof PageOperationHandler) {
            return (PageOperationHandler) t;
        } else {
            if (useThreadPool) {
                return pohFactory.getPageOperationHandler();
            } else {
                return new PageOperationHandler.DummyPageOperationHandler();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <R> PageOperation.Listener<R> getPageOperationListener() {
        Object object = Thread.currentThread();
        PageOperation.Listener<R> listener;
        if (object instanceof PageOperation.Listener)
            listener = (PageOperation.Listener<R>) object;
        else if (object instanceof PageOperation.ListenerFactory)
            listener = ((PageOperation.ListenerFactory<R>) object).createListener();
        else
            listener = new PageOperation.SyncListener<R>();
        listener.startListen();
        return listener;
    }

    ////////////////////// 以下是分布式API的实现 ////////////////////////////////

    private boolean isShardingMode;
    private IDatabase db;
    private String[] oldNodes;

    public IDatabase getDatabase() {
        return db;
    }

    public void setDatabase(IDatabase db) {
        this.db = db;
    }

    public void replicateRootPage(DataBuffer buff) {
        root.replicatePage(buff);
    }

    public void setOldNodes(String[] oldNodes) {
        this.oldNodes = oldNodes;
    }

    public void setRunMode(RunMode runMode) {
        isShardingMode = runMode == RunMode.SHARDING;
    }

    public boolean isShardingMode() {
        return isShardingMode;
    }

    private String getLocalHostId() {
        return db.getLocalHostId();
    }

    private Set<NetNode> getCandidateNodes() {
        return getCandidateNodes(db, db.getHostIds());
    }

    public static Set<NetNode> getCandidateNodes(IDatabase db, String[] hostIds) {
        Set<NetNode> candidateNodes = new HashSet<>(hostIds.length);
        for (String hostId : hostIds) {
            candidateNodes.add(db.getNode(hostId));
        }
        return candidateNodes;
    }

    // 必需同步
    private synchronized Page setLeafPageMovePlan(PageKey pageKey, LeafPageMovePlan leafPageMovePlan) {
        Page page = root.binarySearchLeafPage(pageKey.key);
        if (page != null) {
            page.setLeafPageMovePlan(leafPageMovePlan);
        }
        return page;
    }

    public void fireLeafPageSplit(Object splitKey) {
        if (isShardingMode()) {
            PageKey pk = new PageKey(splitKey, false); // 移动右边的Page
            moveLeafPageLazy(pk);
        }
    }

    private void moveLeafPageLazy(PageKey pageKey) {
        RunnableOperation operation = new RunnableOperation(() -> {
            moveLeafPage(pageKey);
        });
        pohFactory.addPageOperation(operation);
    }

    private void moveLeafPage(PageKey pageKey) {
        Page p = root;
        Page parent = p;
        int index = 0;
        while (p.isNode()) {
            index = p.binarySearch(pageKey.key);
            if (index < 0) {
                index = -index - 1;
                if (p.isRemoteChildPage(index))
                    return;
                parent = p;
                p = p.getChildPage(index);
            } else {
                index++;
                if (parent.isRemoteChildPage(index))
                    return;
                // 左边已经移动过了，那么右边就不需要再移
                if (parent.isRemoteChildPage(index - 1))
                    return;

                p = parent.getChildPage(index);
                String[] oldNodes;
                if (p.getReplicationHostIds() == null) {
                    oldNodes = new String[0];
                } else {
                    oldNodes = new String[p.getReplicationHostIds().size()];
                    p.getReplicationHostIds().toArray(oldNodes);
                }
                replicateOrMovePage(pageKey, p, parent, index, oldNodes, false);
                break;
            }
        }
    }

    // 处理三种场景:
    // 1. 从client_server模式转到sharding模式
    // 2. 从replication模式转到sharding模式
    // 3. 在sharding模式下发生page split时需要移动右边的page
    //
    // 前两种场景在移动page时所选定的目标节点可以是原来的节点，后一种不可以。
    // 除此之外，这三者并没有多大差异，只是oldNodes中包含的节点个数多少的问题，
    // client_server模式只有一个节点，在replication模式下，如果副本个数是1，那么也相当于client_server模式。
    private void replicateOrMovePage(PageKey pageKey, Page p, Page parent, int index, String[] oldNodes,
            boolean replicate) {
        Set<NetNode> candidateNodes = getCandidateNodes();
        replicateOrMovePage(pageKey, p, parent, index, oldNodes, replicate, candidateNodes, null);
    }

    public void replicateOrMovePage(PageKey pageKey, Page p, Page parent, int index, String[] oldNodes,
            boolean replicate, Set<NetNode> candidateNodes, RunMode newRunMode) {
        if (oldNodes == null || oldNodes.length == 0) {
            DbException.throwInternalError("oldNodes is null");
        }

        List<NetNode> oldReplicationNodes = getReplicationNodes(db, oldNodes);
        Set<NetNode> oldNodeSet;
        if (replicate) {
            // 允许选择原来的节点，所以用new HashSet<>(0)替代new HashSet<>(oldReplicationNodes)
            oldNodeSet = new HashSet<>(0);
        } else {
            oldNodeSet = new HashSet<>(oldReplicationNodes);
        }

        List<NetNode> newReplicationNodes = db.getReplicationNodes(oldNodeSet, candidateNodes);
        LeafPageMovePlan leafPageMovePlan = null;

        // 如果新的RunMode是CLIENT_SERVER或REPLICATION，那么目标节点都是确定的，所以不需要进行prepareMoveLeafPage
        // 如果当前page所在的节点只有一个，也不需要进行prepareMoveLeafPage
        if (oldNodes.length == 1 || (newRunMode == RunMode.CLIENT_SERVER || newRunMode == RunMode.REPLICATION)) {
            leafPageMovePlan = new LeafPageMovePlan(oldNodes[0], newReplicationNodes, pageKey);
            p.setLeafPageMovePlan(leafPageMovePlan);
        } else {
            Session s = db.createSession(oldReplicationNodes);
            try (StorageCommand c = s.createStorageCommand()) {
                LeafPageMovePlan plan = new LeafPageMovePlan(getLocalHostId(), newReplicationNodes, pageKey);
                leafPageMovePlan = c.prepareMoveLeafPage(getName(), plan).get();
            }

            if (leafPageMovePlan == null)
                return;

            // 重新按key找到page，因为经过前面的操作后，
            // 可能page已经有新数据了，如果只移动老的，会丢失数据
            p = setLeafPageMovePlan(pageKey, leafPageMovePlan);

            if (!leafPageMovePlan.moverHostId.equals(getLocalHostId())) {
                p.setReplicationHostIds(leafPageMovePlan.getReplicationNodes());
                return;
            }
        }

        p.setReplicationHostIds(toHostIds(db, newReplicationNodes));
        NetNode localNode = getLocalNode();

        Set<NetNode> otherNodes = new HashSet<>(candidateNodes);
        otherNodes.removeAll(newReplicationNodes);

        if (parent != null && !replicate && !newReplicationNodes.contains(localNode)) {
            PageReference r = PageReference.createRemotePageReference(pageKey.key, index == 0);
            r.setReplicationHostIds(p.getReplicationHostIds());
            parent.setChild(index, r);
        }
        if (!replicate) {
            otherNodes.removeAll(oldReplicationNodes);
            newReplicationNodes.removeAll(oldReplicationNodes);
        }

        if (newReplicationNodes.contains(localNode)) {
            newReplicationNodes.remove(localNode);
        }

        // 移动page到新的复制节点(page中包含数据)
        if (!newReplicationNodes.isEmpty()) {
            Session s = db.createSession(newReplicationNodes, true);
            moveLeafPage(leafPageMovePlan.pageKey, p, s, false, !replicate);
        }

        // 当前节点已经不是副本所在节点
        if (parent != null && replicate && otherNodes.contains(localNode)) {
            otherNodes.remove(localNode);
            PageReference r = PageReference.createRemotePageReference(pageKey.key, index == 0);
            r.setReplicationHostIds(p.getReplicationHostIds());
            parent.setChild(index, r);
        }

        // 移动page到其他节点(page中不包含数据，只包含这个page各数据副本所在节点信息)
        if (!otherNodes.isEmpty()) {
            Session s = db.createSession(otherNodes, true);
            moveLeafPage(leafPageMovePlan.pageKey, p, s, true, !replicate);
        }
    }

    private void moveLeafPage(PageKey pageKey, Page page, Session session, boolean remote, boolean addPage) {
        try (DataBuffer buff = DataBuffer.create(); StorageCommand c = session.createStorageCommand()) {
            page.writeLeaf(buff, remote);
            ByteBuffer pageBuffer = buff.getAndFlipBuffer();
            c.moveLeafPage(getName(), pageKey, pageBuffer, addPage);
        }
    }

    private static List<String> toHostIds(IDatabase db, List<NetNode> nodes) {
        List<String> hostIds = new ArrayList<>(nodes.size());
        for (NetNode e : nodes) {
            String id = db.getHostId(e);
            hostIds.add(id);
        }
        return hostIds;
    }

    public void fireLeafPageRemove(PageKey pageKey, Page leafPage) {
        if (isShardingMode()) {
            removeLeafPage(pageKey, leafPage);
        }
    }

    private void removeLeafPage(PageKey pageKey, Page leafPage) {
        if (leafPage.getReplicationHostIds().get(0).equals(getLocalHostId())) {
            RunnableOperation operation = new RunnableOperation(() -> {
                List<NetNode> oldReplicationNodes = getReplicationNodes(leafPage);
                Set<NetNode> otherNodes = getCandidateNodes();
                otherNodes.removeAll(oldReplicationNodes);
                Session s = db.createSession(otherNodes, true);
                try (StorageCommand c = s.createStorageCommand()) {
                    c.removeLeafPage(getName(), pageKey);
                }
            });
            pohFactory.addPageOperation(operation);
        }
    }

    @Override
    public synchronized void addLeafPage(PageKey pageKey, ByteBuffer page, boolean addPage) {
        checkWrite();
        Page newPage = readLeafPage(page, false);
        addLeafPage(pageKey, newPage, addPage);
    }

    private Page readLeafPage(ByteBuffer buff, boolean readStreamPage) {
        return readStreamPage ? readStreamPage(buff) : Page.readLeafPage(this, buff);
    }

    private Page readStreamPage(ByteBuffer buff) {
        Page p = new LeafPage(this);
        int chunkId = 0;
        int offset = buff.position();
        p.read(buff, chunkId, offset, buff.limit(), true);
        return p;
    }

    private synchronized void addLeafPage(PageKey pageKey, Page newPage, boolean addPage) {
        if (pageKey == null) {
            root = newPage;
            return;
        }
        Page p = root;
        Object key = pageKey.key;
        if (p.isLeaf() || p.isRemote()) {
            Page emptyPage = p.isLeaf() ? LeafPage.createEmpty(this) : new RemotePage(this);
            emptyPage.setReplicationHostIds(p.getReplicationHostIds());

            Page left = pageKey.first ? newPage : emptyPage;
            Page right = pageKey.first ? emptyPage : newPage;

            Object[] keys = { key };
            PageReference[] children = { new PageReference(left, key, true), new PageReference(right, key, false) };
            p = Page.createNode(this, keys, children, 0);
            newRoot(p);
        } else {
            Page parent = p;
            int index = 0;
            while (p.isNode()) {
                parent = p;
                index = p.getPageIndex(key);
                PageReference r = p.getChildPageReference(index);
                if (r.isRemotePage()) {
                    break;
                }
                p = p.getChildPage(index);
            }
            Page right = newPage;
            if (addPage) {
                Page left = parent.getChildPage(index);
                parent.setChild(index, right);
                parent.insertNode(index, key, left);
            } else {
                parent.setChild(index, right);
            }
        }
    }

    @Override
    public synchronized void removeLeafPage(PageKey pageKey) {
        checkWrite();
        Page p;
        if (pageKey == null) { // 说明删除的是root leaf page
            p = LeafPage.createEmpty(this);
        } else {
            p = root.copy();
            removeLeafPage(p, pageKey);
            if (p.isNode() && p.isEmpty()) {
                p.removePage();
                p = LeafPage.createEmpty(this);
            }
        }
        newRoot(p);
    }

    private void removeLeafPage(Page p, PageKey pk) {
        if (p.isLeaf()) {
            return;
        }
        // node page
        int x = p.getPageIndex(pk.key);
        if (pk.first && p.isLeafChildPage(x)) {
            x = 0;
        }
        Page cOld = p.getChildPage(x);
        Page c = cOld.copy();
        removeLeafPage(c, pk);
        if (c.isLeaf())
            c.removePage();
        else
            p.setChild(x, c);
        if (p.getKeyCount() == 0) { // 如果p的子节点只剩一个叶子节点时，keyCount为0
            p.setChild(x, (Page) null);
        } else {
            if (c.isLeaf())
                p.remove(x);
        }
    }

    private NetNode getLocalNode() {
        return NetNode.getLocalP2pNode();
    }

    private List<NetNode> getReplicationNodes(Object key) {
        return getReplicationNodes(root, key);
    }

    private List<NetNode> getReplicationNodes(Page p, Object key) {
        if (p.isLeaf() || p.isRemote()) {
            return getReplicationNodes(p);
        }
        // p is a node
        int index = p.getPageIndex(key);
        return getReplicationNodes(p.getChildPage(index), key);
    }

    private List<NetNode> getReplicationNodes(Page p) {
        return getReplicationNodes(db, p.getReplicationHostIds());
    }

    public static List<NetNode> getReplicationNodes(IDatabase db, String[] replicationHostIds) {
        return getReplicationNodes(db, Arrays.asList(replicationHostIds));
    }

    private static List<NetNode> getReplicationNodes(IDatabase db, List<String> replicationHostIds) {
        if (db == null)
            return null;
        int size = replicationHostIds.size();
        List<NetNode> replicationNodes = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            replicationNodes.add(db.getNode(replicationHostIds.get(i)));
        }
        return replicationNodes;
    }

    private List<NetNode> getLastPageReplicationNodes() {
        Page p = root;
        while (true) {
            if (p.isLeaf()) {
                return getReplicationNodes(p);
            }
            p = p.getChildPage(getChildPageCount(p) - 1);
        }
    }

    private List<NetNode> getRemoteReplicationNodes(Page p) {
        if (p.getLeafPageMovePlan() != null) {
            if (p.getLeafPageMovePlan().moverHostId.equals(getLocalHostId())) {
                return new ArrayList<>(p.getLeafPageMovePlan().replicationNodes);
            } else {
                // 不是由当前节点移动的，那么操作就可以忽略了
                return null;
            }
        } else if (p.isRemote()) {
            return getReplicationNodes(db, p.getReplicationHostIds());
        } else {
            return null;
        }
    }

    @Override
    public Future<Object> get(Session session, Object key) {
        List<NetNode> replicationNodes = getReplicationNodes(key);
        Session s = db.createSession(session, replicationNodes);
        try (StorageCommand c = s.createStorageCommand()) {
            return c.get(getName(), key, keyType);
        }
    }

    @Override
    public Future<Object> put(Session session, Object key, Object value, StorageDataType valueType,
            boolean addIfAbsent) {
        List<NetNode> replicationNodes = getReplicationNodes(key);
        return putRemote(session, replicationNodes, key, value, valueType, false, addIfAbsent);
    }

    @SuppressWarnings("unchecked")
    public <R> void putRemote(Page p, K key, V value, boolean addIfAbsent, AsyncHandler<AsyncResult<R>> handler) {
        List<NetNode> replicationNodes = getRemoteReplicationNodes(p);
        if (replicationNodes == null) {
            handler.handle(new AsyncResult<>((R) null));
        } else {
            putRemote(null, replicationNodes, key, value, valueType, true, addIfAbsent).onSuccess(r -> {
                ByteBuffer resultByteBuffer = (ByteBuffer) r;
                handler.handle(new AsyncResult<>((R) getValueType().read(resultByteBuffer)));
            }).onFailure(t -> {
                handler.handle(new AsyncResult<>(t));
            });
        }
    }

    private Future<Object> putRemote(Session session, List<NetNode> replicationNodes, Object key, Object value,
            StorageDataType valueType, boolean raw, boolean addIfAbsent) {
        Session s = db.createSession(session, replicationNodes);
        try (StorageCommand c = s.createStorageCommand()) {
            return c.put(getName(), key, keyType, value, valueType, raw, addIfAbsent);
        }
    }

    @Override
    public Future<Object> append(Session session, Object value, StorageDataType valueType) {
        List<NetNode> replicationNodes = getLastPageReplicationNodes();
        return appendRemote(session, replicationNodes, value, valueType);
    }

    @SuppressWarnings("unchecked")
    public <R> void appendRemote(Page p, V value, AsyncHandler<AsyncResult<R>> handler) {
        List<NetNode> replicationNodes = getRemoteReplicationNodes(p);
        if (replicationNodes == null) {
            handler.handle(new AsyncResult<>((R) null));
        } else {
            appendRemote(null, replicationNodes, value, valueType).onSuccess(r -> {
                ByteBuffer resultByteBuffer = (ByteBuffer) r;
                handler.handle(new AsyncResult<>((R) getKeyType().read(resultByteBuffer)));
            }).onFailure(t -> {
                handler.handle(new AsyncResult<>(t));
            });
        }
    }

    private Future<Object> appendRemote(Session session, List<NetNode> replicationNodes, Object value,
            StorageDataType valueType) {
        Session s = db.createSession(session, replicationNodes);
        try (StorageCommand c = s.createStorageCommand()) {
            return c.append(getName(), value, valueType);
        }
    }

    @Override
    public Future<Boolean> replace(Session session, Object key, Object oldValue, Object newValue,
            StorageDataType valueType) {
        List<NetNode> replicationNodes = getReplicationNodes(key);
        return replaceRemote(session, replicationNodes, key, oldValue, newValue, valueType);
    }

    public void replaceRemote(Page p, K key, V oldValue, V newValue, AsyncHandler<AsyncResult<Boolean>> handler) {
        List<NetNode> replicationNodes = getRemoteReplicationNodes(p);
        if (replicationNodes == null) {
            handler.handle(new AsyncResult<>(false));
        } else {
            replaceRemote(null, replicationNodes, key, oldValue, newValue, valueType).onSuccess(r -> {
                handler.handle(new AsyncResult<>(r));
            }).onFailure(t -> {
                handler.handle(new AsyncResult<>(t));
            });
        }
    }

    private Future<Boolean> replaceRemote(Session session, List<NetNode> replicationNodes, Object key, Object oldValue,
            Object newValue, StorageDataType valueType) {
        Session s = db.createSession(session, replicationNodes);
        try (StorageCommand c = s.createStorageCommand()) {
            return c.replace(getName(), key, keyType, oldValue, newValue, valueType);
        }
    }

    @Override
    public Future<Object> remove(Session session, Object key) {
        List<NetNode> replicationNodes = getReplicationNodes(key);
        return removeRemote(session, replicationNodes, key);
    }

    @SuppressWarnings("unchecked")
    public <R> void removeRemote(Page p, K key, AsyncHandler<AsyncResult<R>> handler) {
        List<NetNode> replicationNodes = getRemoteReplicationNodes(p);
        if (replicationNodes == null) {
            handler.handle(new AsyncResult<>((R) null));
        } else {
            removeRemote(null, replicationNodes, key).onSuccess(r -> {
                ByteBuffer resultByteBuffer = (ByteBuffer) r;
                handler.handle(new AsyncResult<>((R) getValueType().read(resultByteBuffer)));
            }).onFailure(t -> {
                handler.handle(new AsyncResult<>(t));
            });
        }
    }

    private Future<Object> removeRemote(Session session, List<NetNode> replicationNodes, Object key) {
        Session s = db.createSession(session, replicationNodes);
        try (StorageCommand c = s.createStorageCommand()) {
            return c.remove(getName(), key, keyType);
        }
    }

    @Override
    public synchronized LeafPageMovePlan prepareMoveLeafPage(LeafPageMovePlan leafPageMovePlan) {
        Page p = root.binarySearchLeafPage(leafPageMovePlan.pageKey.key);
        if (p.isLeaf()) {
            // 老的index < 新的index时，说明上一次没有达成一致，进行第二次协商
            if (p.getLeafPageMovePlan() == null || p.getLeafPageMovePlan().getIndex() < leafPageMovePlan.getIndex()) {
                p.setLeafPageMovePlan(leafPageMovePlan);
            }
            return p.getLeafPageMovePlan();
        }
        return null;
    }

    @Override
    public ByteBuffer readPage(PageKey pageKey) {
        Page p = root;
        Object k = pageKey.key;
        if (p.isLeaf()) {
            throw DbException.getInternalError("readPage: pageKey=" + pageKey);
        }
        Page parent = p;
        int index = 0;
        while (p.isNode()) {
            index = p.binarySearch(k);
            if (index < 0) {
                index = -index - 1;
                parent = p;
                p = p.getChildPage(index);
            } else {
                // 第一和第二个child使用同一个pageKey，
                // 如果此时first为true，就不需要增加index了
                if (!pageKey.first)
                    index++;
                return replicateOrMovePage(pageKey, parent.getChildPage(index), parent, index);
            }
        }
        return null;
    }

    private ByteBuffer replicateOrMovePage(PageKey pageKey, Page p, Page parent, int index) {
        // 从client_server模式到replication模式
        if (!isShardingMode()) {
            return replicatePage(p);
        }

        // 如果该page已经处理过，那么直接返回它
        if (p.getReplicationHostIds() != null) {
            return replicatePage(p);
        }

        // 以下处理从client_server或replication模式到sharding模式的场景
        // ---------------------------------------------------------------
        replicateOrMovePage(pageKey, p, parent, index, oldNodes, true);

        return replicatePage(p);
    }

    private ByteBuffer replicatePage(Page p) {
        try (DataBuffer buff = DataBuffer.create()) {
            p.replicatePage(buff);
            ByteBuffer pageBuffer = buff.getAndCopyBuffer();
            return pageBuffer;
        }
    }

    public synchronized void readRootPageFrom(ByteBuffer data) {
        root = Page.readReplicatedPage(this, data);
        if (root.isNode() && !getName().endsWith("_0")) { // 只异步读非SYS表
            root.readRemotePages();
        }
    }

    public void moveAllLocalLeafPages(String[] oldNodes, String[] newNodes, RunMode newRunMode) {
        if (root.isNode() || (root.isLeaf() && !root.isEmpty()))
            root.moveAllLocalLeafPages(oldNodes, newNodes, newRunMode);
    }

    // 查找闭区间[from, to]对应的所有leaf page，并建立这些leaf page所在节点与page key的映射关系
    // 该方法不需要读取leaf page或remote page
    @Override
    public Map<List<String>, List<PageKey>> getNodeToPageKeyMap(K from, K to) {
        return getNodeToPageKeyMap(from, to, null);
    }

    public Map<List<String>, List<PageKey>> getNodeToPageKeyMap(K from, K to, List<PageKey> pageKeys) {
        Map<List<String>, List<PageKey>> map = new HashMap<>();
        if (root.isLeaf()) {
            Object key = root.getKeyCount() == 0 ? ValueNull.INSTANCE : root.getKey(0);
            getPageKey(map, pageKeys, root, 0, key);
        } else if (root.isRemote()) {
            getPageKey(map, pageKeys, root, 0, ValueNull.INSTANCE);
        } else {
            dfs(map, from, to, pageKeys);
        }
        return map;
    }

    // 深度优先搜索(不使用递归)
    private void dfs(Map<List<String>, List<PageKey>> map, K from, K to, List<PageKey> pageKeys) {
        CursorPos pos = null;
        Page p = root;
        while (p.isNode()) {
            // 注意: index是子page的数组索引，不是keys数组的索引
            int index = from == null ? 0 : p.getPageIndex(from);
            pos = new CursorPos(p, index + 1, pos);
            if (p.isNodeChildPage(index)) {
                p = p.getChildPage(index);
            } else {
                getPageKeys(map, from, to, pageKeys, p, index);

                // from此时为null，代表从右边兄弟节点keys数组的0号索引开始
                from = null;
                // 转到上一层，遍历右边的兄弟节点
                for (;;) {
                    pos = pos.parent;
                    if (pos == null) {
                        return;
                    }
                    if (pos.index < getChildPageCount(pos.page)) {
                        if (pos.page.isNodeChildPage(pos.index)) {
                            p = pos.page.getChildPage(pos.index++);
                            break; // 只是退出for循环
                        }
                    }
                }
            }
        }
    }

    private void getPageKeys(Map<List<String>, List<PageKey>> map, K from, K to, List<PageKey> pageKeys, Page p,
            int index) {
        int keyCount = p.getKeyCount();
        if (keyCount > 1) {
            boolean needsCompare = false;
            if (to != null) {
                Object lastKey = p.getLastKey();
                if (keyType.compare(lastKey, to) >= 0) {
                    needsCompare = true;
                }
            }
            // node page的直接子page不会出现同时包含node page和leaf page的情况
            for (int size = getChildPageCount(p); index < size; index++) {
                int keyIndex = index - 1;
                Object k = p.getKey(keyIndex < 0 ? 0 : keyIndex);
                getPageKey(map, pageKeys, p, index, k);
                if (needsCompare && keyType.compare(k, to) > 0) {
                    return;
                }
            }
        } else if (keyCount == 1) {
            Object k = p.getKey(0);
            if (from == null || keyType.compare(from, k) < 0) {
                getPageKey(map, pageKeys, p, 0, k);
            }
            if ((from != null && keyType.compare(from, k) >= 0) //
                    || to == null //
                    || keyType.compare(to, k) >= 0) {
                getPageKey(map, pageKeys, p, 1, k);
            }
        } else { // 当keyCount=0时也是合法的，比如node page只删到剩一个leaf page时
            if (getChildPageCount(p) != 1) {
                throw DbException.getInternalError();
            }
            Object k = p.getChildPageReference(0).getPageKey().key;
            getPageKey(map, pageKeys, p, 0, k);
        }
    }

    private void getPageKey(Map<List<String>, List<PageKey>> map, List<PageKey> pageKeys, Page p, int index,
            Object key) {
        long pos;
        List<String> hostIds;
        if (p.isNode()) {
            PageReference pr = p.getChildPageReference(index);
            pos = pr.getPos();
            hostIds = pr.getReplicationHostIds();
        } else {
            pos = p.getPos();
            hostIds = p.getReplicationHostIds();
        }

        PageKey pk = new PageKey(key, index == 0, pos);
        if (pageKeys != null)
            pageKeys.add(pk);
        if (hostIds != null) {
            List<PageKey> keys = null;
            HashSet<String> set = new HashSet<>(hostIds);
            for (Entry<List<String>, List<PageKey>> e : map.entrySet()) {
                List<String> list = e.getKey();
                if (set.equals(new HashSet<>(list))) {
                    keys = e.getValue();
                    break;
                }
            }
            if (keys == null) {
                keys = new ArrayList<>();
                map.put(hostIds, keys);
            }
            keys.add(pk);
        }
    }
}
