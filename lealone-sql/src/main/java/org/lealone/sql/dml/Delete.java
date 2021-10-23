/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.dml;

import org.lealone.common.util.StringUtils;
import org.lealone.db.api.Trigger;
import org.lealone.db.async.AsyncHandler;
import org.lealone.db.async.AsyncResult;
import org.lealone.db.auth.Right;
import org.lealone.db.result.Row;
import org.lealone.db.session.ServerSession;
import org.lealone.sql.PreparedSQLStatement;
import org.lealone.sql.SQLStatement;
import org.lealone.sql.executor.DefaultYieldableShardingUpdate;
import org.lealone.sql.executor.YieldableBase;
import org.lealone.sql.executor.YieldableConditionUpdateBase;
import org.lealone.sql.expression.Expression;
import org.lealone.sql.optimizer.TableFilter;

/**
 * This class represents the statement
 * DELETE
 * 
 * @author H2 Group
 * @author zhh
 */
public class Delete extends ManipulationStatement {

    private TableFilter tableFilter;
    private Expression condition;

    /**
     * The limit expression as specified in the LIMIT or TOP clause.
     */
    private Expression limitExpr;

    public Delete(ServerSession session) {
        super(session);
    }

    @Override
    public int getType() {
        return SQLStatement.DELETE;
    }

    @Override
    public boolean isCacheable() {
        return true;
    }

    public void setTableFilter(TableFilter tableFilter) {
        this.tableFilter = tableFilter;
    }

    @Override
    public TableFilter getTableFilter() {
        return tableFilter;
    }

    public void setCondition(Expression condition) {
        this.condition = condition;
    }

    public void setLimit(Expression limit) {
        this.limitExpr = limit;
    }

    @Override
    public int getPriority() {
        if (getCurrentRowNumber() > 0)
            return priority;

        priority = NORM_PRIORITY - 1;
        return priority;
    }

    @Override
    public String getPlanSQL() {
        StringBuilder buff = new StringBuilder();
        buff.append("DELETE ");
        buff.append("FROM ").append(tableFilter.getPlanSQL(false));
        if (condition != null) {
            buff.append("\nWHERE ").append(StringUtils.unEnclose(condition.getSQL()));
        }
        if (limitExpr != null) {
            buff.append("\nLIMIT (").append(StringUtils.unEnclose(limitExpr.getSQL())).append(')');
        }
        return buff.toString();
    }

    @Override
    public PreparedSQLStatement prepare() {
        if (condition != null) {
            condition.mapColumns(tableFilter, 0);
            condition = condition.optimize(session);
            condition.createIndexConditions(session, tableFilter);
            tableFilter.createColumnIndexes(condition);
        }
        tableFilter.preparePlan(session, 1);
        return this;
    }

    @Override
    public int update() {
        YieldableDelete yieldable = new YieldableDelete(this, null);
        return syncExecute(yieldable);
    }

    @Override
    public YieldableBase<Integer> createYieldableUpdate(AsyncHandler<AsyncResult<Integer>> asyncHandler) {
        if (isShardingMode())
            return new DefaultYieldableShardingUpdate(this, asyncHandler); // 处理sharding模式
        else
            return new YieldableDelete(this, asyncHandler); // 处理单机模式、复制模式
    }

    private static class YieldableDelete extends YieldableConditionUpdateBase {

        final Delete statement;

        public YieldableDelete(Delete statement, AsyncHandler<AsyncResult<Integer>> asyncHandler) {
            super(statement, asyncHandler, statement.tableFilter, statement.limitExpr, statement.condition);
            this.statement = statement;
        }

        @Override
        protected boolean startInternal() {
            if (!table.trySharedLock(session))
                return true;
            session.getUser().checkRight(table, Right.DELETE);
            tableFilter.startQuery(session);
            tableFilter.reset();
            table.fire(session, Trigger.DELETE, true);
            statement.setCurrentRowNumber(0);
            if (limitRows == 0)
                hasNext = false;
            else
                hasNext = tableFilter.next(); // 提前next，当发生行锁时可以直接用tableFilter的当前值重试
            return false;
        }

        @Override
        protected void stopInternal() {
            table.fire(session, Trigger.DELETE, false);
        }

        @Override
        protected void executeLoopUpdate() {
            rebuildSearchRowIfNeeded();
            while (hasNext && pendingException == null) {
                if (yieldIfNeeded(++loopCount)) {
                    return;
                }
                if (conditionEvaluator.getBooleanValue()) {
                    Row row = tableFilter.get();
                    if (!tryLockRow(row, null))
                        return;
                    boolean done = false;
                    if (table.fireRow()) {
                        done = table.fireBeforeRow(session, row, null);
                    }
                    if (!done) {
                        pendingOperationCount.incrementAndGet();
                        removeRow(row);
                        if (limitRows > 0 && updateCount.get() >= limitRows) {
                            onLoopEnd();
                            return;
                        }
                    }
                }
                hasNext = tableFilter.next();
            }
            onLoopEnd();
        }

        private void removeRow(Row row) {
            table.removeRow(session, row, true).onComplete(ar -> {
                if (ar.isSucceeded() && table.fireRow()) {
                    table.fireAfterRow(session, row, null, false);
                }
                onComplete(ar);
            });
        }
    }
}
