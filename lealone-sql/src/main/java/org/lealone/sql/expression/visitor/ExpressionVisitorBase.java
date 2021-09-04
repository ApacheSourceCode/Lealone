/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.sql.expression.visitor;

import org.lealone.sql.expression.Alias;
import org.lealone.sql.expression.Expression;
import org.lealone.sql.expression.ExpressionColumn;
import org.lealone.sql.expression.ExpressionList;
import org.lealone.sql.expression.Operation;
import org.lealone.sql.expression.Parameter;
import org.lealone.sql.expression.Rownum;
import org.lealone.sql.expression.SelectOrderBy;
import org.lealone.sql.expression.SequenceValue;
import org.lealone.sql.expression.Subquery;
import org.lealone.sql.expression.ValueExpression;
import org.lealone.sql.expression.Variable;
import org.lealone.sql.expression.Wildcard;
import org.lealone.sql.expression.aggregate.Aggregate;
import org.lealone.sql.expression.aggregate.JavaAggregate;
import org.lealone.sql.expression.condition.CompareLike;
import org.lealone.sql.expression.condition.Comparison;
import org.lealone.sql.expression.condition.ConditionAndOr;
import org.lealone.sql.expression.condition.ConditionExists;
import org.lealone.sql.expression.condition.ConditionIn;
import org.lealone.sql.expression.condition.ConditionInConstantSet;
import org.lealone.sql.expression.condition.ConditionInSelect;
import org.lealone.sql.expression.condition.ConditionNot;
import org.lealone.sql.expression.function.Function;
import org.lealone.sql.expression.function.JavaFunction;
import org.lealone.sql.expression.function.TableFunction;
import org.lealone.sql.query.Query;

public class ExpressionVisitorBase<R> implements IExpressionVisitor<R> {

    @Override
    public R visitAlias(Alias e) {
        return e.getNonAliasExpression().accept(this);
    }

    @Override
    public R visitExpressionColumn(ExpressionColumn e) {
        return null;
    }

    @Override
    public R visitExpressionList(ExpressionList e) {
        for (Expression e2 : e.getList()) {
            e2.accept(this);
        }
        return null;
    }

    @Override
    public R visitOperation(Operation e) {
        e.getLeft().accept(this);
        if (e.getRight() != null)
            e.getRight().accept(this);
        return null;
    }

    @Override
    public R visitParameter(Parameter e) {
        return null;
    }

    @Override
    public R visitRownum(Rownum e) {
        return null;
    }

    @Override
    public R visitSequenceValue(SequenceValue e) {
        return null;
    }

    @Override
    public R visitSubquery(Subquery e) {
        visitQuery(e.getQuery());
        return null;
    }

    protected R visitQuery(Query query) {
        query.accept(this);
        return null;
    }

    @Override
    public R visitValueExpression(ValueExpression e) {
        return null;
    }

    @Override
    public R visitVariable(Variable e) {
        return null;
    }

    @Override
    public R visitWildcard(Wildcard e) {
        return null;
    }

    @Override
    public R visitCompareLike(CompareLike e) {
        e.getLeft().accept(this);
        e.getRight().accept(this);
        if (e.getEscape() != null)
            e.getEscape().accept(this);
        return null;
    }

    @Override
    public R visitComparison(Comparison e) {
        e.getLeft().accept(this);
        if (e.getRight() != null)
            e.getRight().accept(this);
        return null;
    }

    @Override
    public R visitConditionAndOr(ConditionAndOr e) {
        e.getLeft().accept(this);
        e.getRight().accept(this);
        return null;
    }

    @Override
    public R visitConditionExists(ConditionExists e) {
        visitQuery(e.getQuery());
        return null;
    }

    @Override
    public R visitConditionIn(ConditionIn e) {
        e.getLeft().accept(this);
        for (Expression e2 : e.getValueList()) {
            e2.accept(this);
        }
        return null;
    }

    @Override
    public R visitConditionInConstantSet(ConditionInConstantSet e) {
        e.getLeft().accept(this);
        return null;
    }

    @Override
    public R visitConditionInSelect(ConditionInSelect e) {
        e.getLeft().accept(this);
        visitQuery(e.getQuery());
        return null;
    }

    @Override
    public R visitConditionNot(ConditionNot e) {
        e.getCondition().accept(this);
        return null;
    }

    @Override
    public R visitAggregate(Aggregate e) {
        if (e.getOn() != null)
            e.getOn().accept(this);
        if (e.getGroupConcatSeparator() != null)
            e.getGroupConcatSeparator().accept(this);
        if (e.getGroupConcatOrderList() != null) {
            for (SelectOrderBy o : e.getGroupConcatOrderList()) {
                o.expression.accept(this);
            }
        }
        return null;
    }

    @Override
    public R visitJavaAggregate(JavaAggregate e) {
        for (Expression e2 : e.getArgs()) {
            if (e2 != null)
                e2.accept(this);
        }
        return null;
    }

    @Override
    public R visitFunction(Function e) {
        for (Expression e2 : e.getArgs()) {
            if (e2 != null)
                e2.accept(this);
        }
        return null;
    }

    @Override
    public R visitJavaFunction(JavaFunction e) {
        for (Expression e2 : e.getArgs()) {
            if (e2 != null)
                e2.accept(this);
        }
        return null;
    }

    @Override
    public R visitTableFunction(TableFunction e) {
        for (Expression e2 : e.getArgs()) {
            if (e2 != null)
                e2.accept(this);
        }
        return null;
    }
}
