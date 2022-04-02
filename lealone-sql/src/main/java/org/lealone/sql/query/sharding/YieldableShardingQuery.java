/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.sql.query.sharding;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.lealone.db.async.AsyncHandler;
import org.lealone.db.async.AsyncResult;
import org.lealone.db.result.Result;
import org.lealone.db.session.Session;
import org.lealone.db.session.SessionStatus;
import org.lealone.net.NetNode;
import org.lealone.net.NetNodeManager;
import org.lealone.net.NetNodeManagerHolder;
import org.lealone.server.protocol.dt.DTransactionParameters;
import org.lealone.sql.DistributedSQLCommand;
import org.lealone.sql.SQLStatement;
import org.lealone.sql.StatementBase;
import org.lealone.sql.query.Select;
import org.lealone.sql.query.YieldableQueryBase;
import org.lealone.storage.page.PageKey;

public class YieldableShardingQuery extends YieldableQueryBase {

    private SQOperator queryOperator;

    public YieldableShardingQuery(StatementBase statement, int maxRows, boolean scrollable,
            AsyncHandler<AsyncResult<Result>> asyncHandler) {
        super(statement, maxRows, scrollable, asyncHandler);
    }

    @Override
    protected boolean startInternal() {
        statement.getSession().getTransaction();
        queryOperator = createShardingQueryOperator();
        queryOperator.setSession(session);
        queryOperator.start();
        return false;
    }

    @Override
    protected void executeInternal() {
        if (!queryOperator.end) {
            session.setStatus(SessionStatus.STATEMENT_RUNNING);
            queryOperator.run();
        }
        if (queryOperator.end) {
            if (queryOperator.pendingException != null) {
                setPendingException(queryOperator.pendingException);
            } else {
                setResult(queryOperator.result, queryOperator.result.getRowCount());
            }
            session.setStatus(SessionStatus.STATEMENT_COMPLETED);
        }
    }

    private SQOperator createShardingQueryOperator() {
        switch (statement.getType()) {
        case SQLStatement.SELECT: {
            Map<List<String>, List<PageKey>> nodeToPageKeyMap = statement.getNodeToPageKeyMap();
            // 不支持sharding的表，例如information_schema中预先定义的表
            if (nodeToPageKeyMap == null) {
                return new SQDirect(statement, maxRows);
            }
            if (nodeToPageKeyMap.size() <= 0) {
                return new SQEmpty();
            }
            SQCommand[] commands = createCommands(nodeToPageKeyMap);
            Select select = (Select) statement;
            if (select.isGroupQuery()) {
                return new SQMerge(commands, maxRows, select);
            } else {
                if (select.getSortOrder() != null) {
                    return new SQSort(commands, maxRows, select);
                } else {
                    return new SQSerialize(commands, maxRows, select.getLimitRows());
                }
            }
        }
        default:
            return new SQDirect(statement, maxRows);
        }
    }

    private SQCommand[] createCommands(Map<List<String>, List<PageKey>> nodeToPageKeyMap) {
        String sql = statement.getPlanSQL(true);
        String indexName = statement.getIndexName();
        SQCommand[] commands = new SQCommand[nodeToPageKeyMap.size()];
        int i = 0;
        NetNodeManager m = NetNodeManagerHolder.get();
        for (Entry<List<String>, List<PageKey>> e : nodeToPageKeyMap.entrySet()) {
            Set<NetNode> nodes = m.getNodes(e.getKey());
            List<PageKey> pageKeys = e.getValue();
            Session s = session.getDatabase().createSession(session, nodes);
            DistributedSQLCommand c = s.createDistributedSQLCommand(sql, Integer.MAX_VALUE);
            DTransactionParameters parameters = new DTransactionParameters(pageKeys, indexName, s.isAutoCommit());
            commands[i++] = new SQCommand(c, maxRows, scrollable, parameters);
        }
        return commands;
    }
}
