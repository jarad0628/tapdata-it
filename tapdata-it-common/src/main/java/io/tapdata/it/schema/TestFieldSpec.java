package io.tapdata.it.schema;

/**
 * 测试字段规格：描述一列的语义与生成约束。
 * <p>
 * {@code dataType} 为被测 Connector 的方言类型（如 DB2 i 的 {@code VARCHAR(255)}、MongoDB 的 {@code Int32}），
 * 由 {@link io.tapdata.it.mapping.TapTypeResolver} 基于 connector spec.json dataTypes 自动解析断言；
 * {@code testDataType} 决定数据生成器类型。
 */
public class TestFieldSpec {

    private final String name;
    /** 通用类型名（含长度/精度），如 varchar(255)、decimal(18,4) */
    private final String dataType;
    /** 生成器选择依据 */
    private final TestDataType testDataType;
    private final Integer length;
    private final Integer scale;
    private final Integer precision;
    private final boolean primaryKey;
    private final boolean autoInc;
    private final boolean nullable;
    /** 固定值：设置后生成器进入固定值模式（边界测试场景） */
    private final Object fixedValue;

    private TestFieldSpec(Builder builder) {
        this.name = builder.name;
        this.dataType = builder.dataType;
        this.testDataType = builder.testDataType;
        this.length = builder.length;
        this.scale = builder.scale;
        this.precision = builder.precision;
        this.primaryKey = builder.primaryKey;
        this.autoInc = builder.autoInc;
        this.nullable = builder.nullable;
        this.fixedValue = builder.fixedValue;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getName() {
        return name;
    }

    public String getDataType() {
        return dataType;
    }

    public TestDataType getTestDataType() {
        return testDataType;
    }

    public Integer getLength() {
        return length;
    }

    public Integer getScale() {
        return scale;
    }

    public Integer getPrecision() {
        return precision;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public boolean isAutoInc() {
        return autoInc;
    }

    public boolean isNullable() {
        return nullable;
    }

    public Object getFixedValue() {
        return fixedValue;
    }

    public static class Builder {
        private String name;
        private String dataType;
        private TestDataType testDataType;
        private Integer length;
        private Integer scale;
        private Integer precision;
        private boolean primaryKey;
        private boolean autoInc;
        private boolean nullable = true;
        private Object fixedValue;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder dataType(String dataType) {
            this.dataType = dataType;
            return this;
        }

        public Builder testDataType(TestDataType testDataType) {
            this.testDataType = testDataType;
            return this;
        }

        public Builder length(Integer length) {
            this.length = length;
            return this;
        }

        public Builder scale(Integer scale) {
            this.scale = scale;
            return this;
        }

        public Builder precision(Integer precision) {
            this.precision = precision;
            return this;
        }

        public Builder primaryKey(boolean primaryKey) {
            this.primaryKey = primaryKey;
            return this;
        }

        public Builder autoInc(boolean autoInc) {
            this.autoInc = autoInc;
            return this;
        }

        public Builder nullable(boolean nullable) {
            this.nullable = nullable;
            return this;
        }

        public Builder fixedValue(Object fixedValue) {
            this.fixedValue = fixedValue;
            return this;
        }

        public TestFieldSpec build() {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("field name must not be empty");
            }
            if (dataType == null) {
                throw new IllegalArgumentException("field dataType must not be null: " + name);
            }
            if (testDataType == null) {
                throw new IllegalArgumentException("field testDataType must not be null: " + name);
            }
            return new TestFieldSpec(this);
        }
    }
}
