package dev.morphia.aggregation.experimental.stages;

import java.util.List;

public abstract class Expression {
    protected final String operation;
    protected final String name;
    protected final Object value;

    protected Expression(final String operation, final String name, final Object value) {
        this.operation = operation;
        this.name = name;
        this.value = value;
    }

    public static Expression field(final String name) {
        return new Literal(name.startsWith("$") ? name : "$" + name);
    }

    public static Expression literal(final Object value) {
        return new Literal(value);
    }

    public String getOperation() {
        return operation;
    }

    public String getName() {
        return name;
    }

    public Object getValue() {
        return value;
    }

    public static class Literal extends Expression {
        public Literal(final Object value) {
            super(null, null, value);
        }
    }
}
