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
import org.lealone.sql.expression.SequenceValue;
import org.lealone.sql.expression.ValueExpression;
import org.lealone.sql.expression.Variable;
import org.lealone.sql.expression.Wildcard;
import org.lealone.sql.expression.aggregate.AGroupConcat;
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
import org.lealone.sql.expression.subquery.SubQuery;
import org.lealone.sql.query.Select;
import org.lealone.sql.query.SelectUnion;

public interface ExpressionVisitor<R> {

    public default ExpressionVisitor<R> incrementQueryLevel(int offset) {
        return this;
    }

    public default int getQueryLevel() {
        return 0;
    }

    R visitExpression(Expression e);

    R visitAlias(Alias e);

    R visitExpressionColumn(ExpressionColumn e);

    R visitExpressionList(ExpressionList e);

    R visitOperation(Operation e);

    R visitParameter(Parameter e);

    R visitRownum(Rownum e);

    R visitSequenceValue(SequenceValue e);

    R visitSubQuery(SubQuery e);

    R visitValueExpression(ValueExpression e);

    R visitVariable(Variable e);

    R visitWildcard(Wildcard e);

    R visitCompareLike(CompareLike e);

    R visitComparison(Comparison e);

    R visitConditionAndOr(ConditionAndOr e);

    R visitConditionExists(ConditionExists e);

    R visitConditionIn(ConditionIn e);

    R visitConditionInConstantSet(ConditionInConstantSet e);

    R visitConditionInSelect(ConditionInSelect e);

    R visitConditionNot(ConditionNot e);

    R visitAggregate(Aggregate e);

    R visitAGroupConcat(AGroupConcat e);

    R visitJavaAggregate(JavaAggregate e);

    R visitFunction(Function e);

    R visitJavaFunction(JavaFunction e);

    R visitTableFunction(TableFunction e);

    R visitSelect(Select s);

    R visitSelectUnion(SelectUnion su);
}
