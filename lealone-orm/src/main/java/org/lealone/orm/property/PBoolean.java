/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.orm.property;

import java.util.Map;

import org.lealone.db.value.Value;
import org.lealone.db.value.ValueBoolean;
import org.lealone.orm.Model;

/**
 * Boolean property.
 *
 * @param <R> the root model bean type
 */
public class PBoolean<R> extends PBaseValueEqual<R, Boolean> {

    private boolean value;

    /**
     * Construct with a property name and root instance.
     *
     * @param name property name
     * @param root the root model bean instance
     */
    public PBoolean(String name, R root) {
        super(name, root);
    }

    private PBoolean<R> P(Model<?> model) {
        return this.<PBoolean<R>> getModelProperty(model);
    }

    /**
     * Is true.
     *
     * @return the root model bean instance
     */
    public R isTrue() {
        Model<?> model = getModel();
        if (model != root) {
            return P(model).isTrue();
        }
        expr().eq(name, Boolean.TRUE);
        return root;
    }

    /**
     * Is false.
     *
     * @return the root model bean instance
     */
    public R isFalse() {
        Model<?> model = getModel();
        if (model != root) {
            return P(model).isFalse();
        }
        expr().eq(name, Boolean.FALSE);
        return root;
    }

    /**
     * Is true or false based on the bind value.
     *
     * @param value the equal to bind value
     *
     * @return the root model bean instance
     */
    public R is(boolean value) {
        Model<?> model = getModel();
        if (model != root) {
            return P(model).is(value);
        }
        expr().eq(name, value);
        return root;
    }

    /**
     * Is true or false based on the bind value.
     *
     * @param value the equal to bind value
     *
     * @return the root model bean instance
     */
    public R eq(boolean value) {
        Model<?> model = getModel();
        if (model != root) {
            return P(model).eq(value);
        }
        expr().eq(name, value);
        return root;
    }

    public final R set(boolean value) {
        Model<?> model = getModel();
        if (model != root) {
            return P(model).set(value);
        }
        if (!areEqual(this.value, value)) {
            this.value = value;
            expr().set(name, ValueBoolean.get(value));
        }
        return root;
    }

    @Override
    public R set(Object value) {
        return set(Boolean.valueOf(value.toString()).booleanValue());
    }

    public final boolean get() {
        Model<?> model = getModel();
        if (model != root) {
            return P(model).get();
        }
        return value;
    }

    @Override
    protected void deserialize(Value v) {
        value = v.getBoolean();
    }

    @Override
    protected void serialize(Map<String, Object> map) {
        map.put(getName(), value ? 1 : 0);
    }

    @Override
    protected void deserialize(Object v) {
        value = ((Number) v).byteValue() != 0;
    }
}
