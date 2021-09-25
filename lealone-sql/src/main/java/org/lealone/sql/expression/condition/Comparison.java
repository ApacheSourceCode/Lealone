/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.expression.condition;

import java.util.ArrayList;
import java.util.Arrays;

import org.lealone.common.exceptions.DbException;
import org.lealone.db.Database;
import org.lealone.db.SysProperties;
import org.lealone.db.session.ServerSession;
import org.lealone.db.value.Value;
import org.lealone.db.value.ValueBoolean;
import org.lealone.db.value.ValueNull;
import org.lealone.sql.expression.Expression;
import org.lealone.sql.expression.ExpressionColumn;
import org.lealone.sql.expression.Parameter;
import org.lealone.sql.expression.ValueExpression;
import org.lealone.sql.expression.visitor.ExpressionVisitorFactory;
import org.lealone.sql.expression.visitor.ExpressionVisitor;
import org.lealone.sql.expression.visitor.NotFromResolverVisitor;
import org.lealone.sql.optimizer.IndexCondition;
import org.lealone.sql.optimizer.TableFilter;
import org.lealone.sql.vector.BooleanVector;
import org.lealone.sql.vector.ValueVector;

/**
 * Example comparison expressions are ID=1, NAME=NAME, NAME IS NULL.
 */
public class Comparison extends Condition {

    /**
     * This is a flag meaning the comparison is null safe (meaning never returns
     * NULL even if one operand is NULL). Only EQUAL and NOT_EQUAL are supported
     * currently.
     */
    public static final int NULL_SAFE = 16;

    /**
     * The comparison type meaning = as in ID=1.
     */
    public static final int EQUAL = 0;

    /**
     * The comparison type meaning ID IS 1 (ID IS NOT DISTINCT FROM 1).
     */
    public static final int EQUAL_NULL_SAFE = EQUAL | NULL_SAFE;

    /**
     * The comparison type meaning &gt;= as in ID&gt;=1.
     */
    public static final int BIGGER_EQUAL = 1;

    /**
     * The comparison type meaning &gt; as in ID&gt;1.
     */
    public static final int BIGGER = 2;

    /**
     * The comparison type meaning &lt;= as in ID&lt;=1.
     */
    public static final int SMALLER_EQUAL = 3;

    /**
     * The comparison type meaning &lt; as in ID&lt;1.
     */
    public static final int SMALLER = 4;

    /**
     * The comparison type meaning &lt;&gt; as in ID&lt;&gt;1.
     */
    public static final int NOT_EQUAL = 5;

    /**
     * The comparison type meaning ID IS NOT 1 (ID IS DISTINCT FROM 1).
     */
    public static final int NOT_EQUAL_NULL_SAFE = NOT_EQUAL | NULL_SAFE;

    /**
     * The comparison type meaning IS NULL as in NAME IS NULL.
     */
    public static final int IS_NULL = 6;

    /**
     * The comparison type meaning IS NOT NULL as in NAME IS NOT NULL.
     */
    public static final int IS_NOT_NULL = 7;

    /**
     * This is a pseudo comparison type that is only used for index conditions.
     * It means the comparison will always yield FALSE. Example: 1=0.
     */
    public static final int FALSE = 8;

    /**
     * This is a pseudo comparison type that is only used for index conditions.
     * It means equals any value of a list. Example: IN(1, 2, 3).
     */
    public static final int IN_LIST = 9;

    /**
     * This is a pseudo comparison type that is only used for index conditions.
     * It means equals any value of a list. Example: IN(SELECT ...).
     */
    public static final int IN_QUERY = 10;

    private final Database database;
    private int compareType;
    private Expression left;
    private Expression right;

    public Comparison(ServerSession session, int compareType, Expression left, Expression right) {
        this.database = session.getDatabase();
        this.left = left;
        this.right = right;
        this.compareType = compareType;
    }

    public Expression getLeft() {
        return left;
    }

    public Expression getRight() {
        return right;
    }

    public int getCompareType() {
        return compareType;
    }

    @Override
    public String getSQL(boolean isDistributed) {
        String sql;
        switch (compareType) {
        case IS_NULL:
            sql = left.getSQL(isDistributed) + " IS NULL";
            break;
        case IS_NOT_NULL:
            sql = left.getSQL(isDistributed) + " IS NOT NULL";
            break;
        default:
            sql = left.getSQL(isDistributed) + " " + getCompareOperator(compareType) + " "
                    + right.getSQL(isDistributed);
        }
        return "(" + sql + ")";
    }

    /**
     * Get the comparison operator string ("=", ">",...).
     *
     * @param compareType the compare type
     * @return the string
     */
    static String getCompareOperator(int compareType) {
        switch (compareType) {
        case EQUAL:
            return "=";
        case EQUAL_NULL_SAFE:
            return "IS";
        case BIGGER_EQUAL:
            return ">=";
        case BIGGER:
            return ">";
        case SMALLER_EQUAL:
            return "<=";
        case SMALLER:
            return "<";
        case NOT_EQUAL:
            return "<>";
        case NOT_EQUAL_NULL_SAFE:
            return "IS NOT";
        default:
            throw DbException.getInternalError("compareType=" + compareType);
        }
    }

    @Override
    public Expression optimize(ServerSession session) {
        left = left.optimize(session);
        if (right != null) {
            right = right.optimize(session);
            if (right instanceof ExpressionColumn) {
                if (left.isConstant() || left instanceof Parameter) {
                    Expression temp = left;
                    left = right;
                    right = temp;
                    compareType = getReversedCompareType(compareType);
                }
            }
            if (left instanceof ExpressionColumn) {
                if (right.isConstant()) {
                    Value r = right.getValue(session);
                    if (r == ValueNull.INSTANCE) {
                        if ((compareType & NULL_SAFE) == 0) {
                            return ValueExpression.getNull();
                        }
                    }
                } else if (right instanceof Parameter) {
                    ((Parameter) right).setColumn(((ExpressionColumn) left).getColumn());
                }
            }
        }
        if (compareType == IS_NULL || compareType == IS_NOT_NULL) {
            if (left.isConstant()) {
                return ValueExpression.get(getValue(session));
            }
        } else {
            if (SysProperties.CHECK && (left == null || right == null)) {
                DbException.throwInternalError();
            }
            if (left == ValueExpression.getNull() || right == ValueExpression.getNull()) {
                // TODO NULL handling: maybe issue a warning when comparing with
                // a NULL constants
                if ((compareType & NULL_SAFE) == 0) {
                    return ValueExpression.getNull();
                }
            }
            if (left.isConstant() && right.isConstant()) {
                return ValueExpression.get(getValue(session));
            }
        }
        return this;
    }

    @Override
    public Value getValue(ServerSession session) {
        Value l = left.getValue(session);
        if (right == null) {
            boolean result;
            switch (compareType) {
            case IS_NULL:
                result = l == ValueNull.INSTANCE;
                break;
            case IS_NOT_NULL:
                result = !(l == ValueNull.INSTANCE);
                break;
            default:
                throw DbException.getInternalError("type=" + compareType);
            }
            return ValueBoolean.get(result);
        }
        // 对于不是is x或is not x的场景，如果left或right有一个是null，那么返回值也是null
        if (l == ValueNull.INSTANCE) {
            if ((compareType & NULL_SAFE) == 0) {
                return ValueNull.INSTANCE;
            }
        }
        Value r = right.getValue(session);
        if (r == ValueNull.INSTANCE) {
            if ((compareType & NULL_SAFE) == 0) {
                return ValueNull.INSTANCE;
            }
        }
        int dataType = Value.getHigherOrder(left.getType(), right.getType());
        l = l.convertTo(dataType);
        r = r.convertTo(dataType);
        boolean result = compareNotNull(database, l, r, compareType);
        return ValueBoolean.get(result);
    }

    @Override
    public ValueVector getValueVector(ServerSession session, ValueVector bvv) {
        ValueVector l = left.getValueVector(session, bvv);
        if (right == null) {
            BooleanVector result;
            switch (compareType) {
            case IS_NULL:
                result = l.isNull();
                break;
            case IS_NOT_NULL:
                result = l.isNotNull();
                break;
            default:
                throw DbException.getInternalError("type=" + compareType);
            }
            return result;
        }
        ValueVector r = right.getValueVector(session, bvv);
        return l.compare(r, compareType);
    }

    /**
     * Compare two values, given the values are not NULL.
     *
     * @param database the database
     * @param l the first value
     * @param r the second value
     * @param compareType the compare type
     * @return the result of the comparison (1 if the first value is bigger, -1
     *         if smaller, 0 if both are equal)
     */
    public static boolean compareNotNull(Database database, Value l, Value r, int compareType) {
        boolean result;
        switch (compareType) {
        case EQUAL:
        case EQUAL_NULL_SAFE:
            result = database.areEqual(l, r);
            break;
        case NOT_EQUAL:
        case NOT_EQUAL_NULL_SAFE:
            result = !database.areEqual(l, r);
            break;
        case BIGGER_EQUAL:
            result = database.compare(l, r) >= 0;
            break;
        case BIGGER:
            result = database.compare(l, r) > 0;
            break;
        case SMALLER_EQUAL:
            result = database.compare(l, r) <= 0;
            break;
        case SMALLER:
            result = database.compare(l, r) < 0;
            break;
        default:
            throw DbException.getInternalError("type=" + compareType);
        }
        return result;
    }

    private int getReversedCompareType(int type) {
        switch (compareType) {
        case EQUAL:
        case EQUAL_NULL_SAFE:
        case NOT_EQUAL:
        case NOT_EQUAL_NULL_SAFE:
            return type;
        case BIGGER_EQUAL:
            return SMALLER_EQUAL;
        case BIGGER:
            return SMALLER;
        case SMALLER_EQUAL:
            return BIGGER_EQUAL;
        case SMALLER:
            return BIGGER;
        default:
            throw DbException.getInternalError("type=" + compareType);
        }
    }

    private int getNotCompareType() {
        switch (compareType) {
        case EQUAL:
            return NOT_EQUAL;
        case EQUAL_NULL_SAFE:
            return NOT_EQUAL_NULL_SAFE;
        case NOT_EQUAL:
            return EQUAL;
        case NOT_EQUAL_NULL_SAFE:
            return EQUAL_NULL_SAFE;
        case BIGGER_EQUAL:
            return SMALLER;
        case BIGGER:
            return SMALLER_EQUAL;
        case SMALLER_EQUAL:
            return BIGGER;
        case SMALLER:
            return BIGGER_EQUAL;
        case IS_NULL:
            return IS_NOT_NULL;
        case IS_NOT_NULL:
            return IS_NULL;
        default:
            throw DbException.getInternalError("type=" + compareType);
        }
    }

    @Override
    public Expression getNotIfPossible(ServerSession session) {
        int type = getNotCompareType();
        return new Comparison(session, type, left, right);
    }

    @Override
    public void createIndexConditions(ServerSession session, TableFilter filter) {
        ExpressionColumn l = null;
        if (left instanceof ExpressionColumn) {
            l = (ExpressionColumn) left;
            if (filter != l.getTableFilter()) {
                l = null;
            }
        }
        if (right == null) {
            if (l != null) {
                switch (compareType) {
                case IS_NULL:
                    if (session.getDatabase().getSettings().optimizeIsNull) {
                        filter.addIndexCondition(
                                IndexCondition.get(Comparison.EQUAL_NULL_SAFE, l, ValueExpression.getNull()));
                    }
                }
            }
            return;
        }
        ExpressionColumn r = null;
        if (right instanceof ExpressionColumn) {
            r = (ExpressionColumn) right;
            if (filter != r.getTableFilter()) {
                r = null;
            }
        }
        // one side must be from the current filter
        if (l == null && r == null) {
            return;
        }
        if (l != null && r != null) {
            return;
        }
        if (l == null) {
            NotFromResolverVisitor visitor = ExpressionVisitorFactory.getNotFromResolverVisitor(filter);
            if (!left.accept(visitor)) {
                return;
            }
        } else if (r == null) {
            NotFromResolverVisitor visitor = ExpressionVisitorFactory.getNotFromResolverVisitor(filter);
            if (!right.accept(visitor)) {
                return;
            }
        } else {
            // if both sides are part of the same filter, it can't be used for
            // index lookup
            return;
        }
        boolean addIndex;
        switch (compareType) {
        case NOT_EQUAL:
        case NOT_EQUAL_NULL_SAFE:
            addIndex = false;
            break;
        case EQUAL:
        case EQUAL_NULL_SAFE:
        case BIGGER:
        case BIGGER_EQUAL:
        case SMALLER_EQUAL:
        case SMALLER:
            addIndex = true;
            break;
        default:
            throw DbException.getInternalError("type=" + compareType);
        }
        if (addIndex) {
            if (l != null) {
                filter.addIndexCondition(IndexCondition.get(compareType, l, right));
            } else if (r != null) {
                int compareRev = getReversedCompareType(compareType);
                filter.addIndexCondition(IndexCondition.get(compareRev, r, left));
            }
        }
    }

    @Override
    public void addFilterConditions(TableFilter filter, boolean outerJoin) {
        if (compareType == IS_NULL && outerJoin) {
            // can not optimize:
            // select * from test t1 left join test t2 on t1.id = t2.id where t2.id is null
            // to
            // select * from test t1 left join test t2 on t1.id = t2.id and t2.id is null
            return;
        }
        super.addFilterConditions(filter, outerJoin);
    }

    @Override
    public int getCost() {
        return left.getCost() + (right == null ? 0 : right.getCost()) + 1;
    }

    /**
     * Get the other expression if this is an equals comparison and the other
     * expression matches.
     *
     * @param match the expression that should match
     * @return null if no match, the other expression if there is a match
     */
    Expression getIfEquals(Expression match) {
        if (compareType == EQUAL) {
            String sql = match.getSQL();
            if (left.getSQL().equals(sql)) {
                return right;
            } else if (right.getSQL().equals(sql)) {
                return left;
            }
        }
        return null;
    }

    /**
     * Get an additional condition if possible. Example: given two conditions
     * A=B AND B=C, the new condition A=C is returned. Given the two conditions
     * A=1 OR A=2, the new condition A IN(1, 2) is returned.
     *
     * @param session the session
     * @param other the second condition
     * @param and true for AND, false for OR
     * @return null or the third condition
     */
    Expression getAdditional(ServerSession session, Comparison other, boolean and) {
        if (compareType == other.compareType && compareType == EQUAL) {
            boolean lc = left.isConstant(), rc = right.isConstant();
            boolean l2c = other.left.isConstant(), r2c = other.right.isConstant();
            String l = left.getSQL();
            String l2 = other.left.getSQL();
            String r = right.getSQL();
            String r2 = other.right.getSQL();
            if (and) {
                // a=b AND a=c
                // must not compare constants. example: NOT(B=2 AND B=3)
                if (!(rc && r2c) && l.equals(l2)) {
                    return new Comparison(session, EQUAL, right, other.right);
                } else if (!(rc && l2c) && l.equals(r2)) {
                    return new Comparison(session, EQUAL, right, other.left);
                } else if (!(lc && r2c) && r.equals(l2)) {
                    return new Comparison(session, EQUAL, left, other.right);
                } else if (!(lc && l2c) && r.equals(r2)) {
                    return new Comparison(session, EQUAL, left, other.left);
                }
            } else {
                // a=b OR a=c
                Database db = session.getDatabase();
                if (rc && r2c && l.equals(l2)) {
                    return new ConditionIn(db, left, new ArrayList<>(Arrays.asList(right, other.right)));
                } else if (rc && l2c && l.equals(r2)) {
                    return new ConditionIn(db, left, new ArrayList<>(Arrays.asList(right, other.left)));
                } else if (lc && r2c && r.equals(l2)) {
                    return new ConditionIn(db, right, new ArrayList<>(Arrays.asList(left, other.right)));
                } else if (lc && l2c && r.equals(r2)) {
                    return new ConditionIn(db, right, new ArrayList<>(Arrays.asList(left, other.left)));
                }
            }
        }
        return null;
    }

    /**
     * Get the left or the right sub-expression of this condition.
     *
     * @param getLeft true to get the left sub-expression, false to get the right
     *            sub-expression.
     * @return the sub-expression
     */
    public Expression getExpression(boolean getLeft) {
        return getLeft ? this.left : right;
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitComparison(this);
    }
}
