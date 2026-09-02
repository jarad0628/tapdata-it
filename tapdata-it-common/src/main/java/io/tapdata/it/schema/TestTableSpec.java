package io.tapdata.it.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 测试表规格：表名 + 有序字段列表 + 默认随机行数。
 * <p>
 * 表名随机生成（{@code _tap_it_ + 时间戳Base36 + 随机串}），避免依赖/污染被测环境既有数据；
 * 每次用例独立建表，由基类 {@code @AfterEach} 统一删除。
 */
public class TestTableSpec {

    private final String tableName;
    private final List<TestFieldSpec> fields;
    private final int recordCount;

    private TestTableSpec(Builder builder) {
        this.tableName = builder.tableName;
        this.fields = Collections.unmodifiableList(new ArrayList<>(builder.fields));
        this.recordCount = builder.recordCount;
    }

    /**
     * 默认全类型表规格：覆盖 int/bigint/varchar/decimal/float/double/boolean/date/datetime/timestamp。
     * <p>
     * 大对象类型（TEXT/BLOB）不在默认规格内：AS400 等数据源不支持 CLOB/BLOB，
     * 需要的数据源通过 {@link #withOptionalLargeObjectTypes()} 或 {@link #optionalTextField()}/{@link #optionalBlobField()} 显式启用。
     */
    public static TestTableSpec defaultAllTypesSpec() {
        return builder()
                .tableName(randomTableName("_tap_it_"))
                .addField(TestFieldSpec.builder().name("id").dataType("bigint").testDataType(TestDataType.BIGINT).primaryKey(true).build())
                .addField(TestFieldSpec.builder().name("c_int").dataType("int").testDataType(TestDataType.INT).build())
                .addField(TestFieldSpec.builder().name("c_bigint").dataType("bigint").testDataType(TestDataType.BIGINT).build())
                .addField(TestFieldSpec.builder().name("c_varchar").dataType("varchar(255)").testDataType(TestDataType.VARCHAR).build())
                .addField(TestFieldSpec.builder().name("c_decimal").dataType("decimal(18,4)").testDataType(TestDataType.DECIMAL).build())
                .addField(TestFieldSpec.builder().name("c_float").dataType("float").testDataType(TestDataType.FLOAT).build())
                .addField(TestFieldSpec.builder().name("c_double").dataType("double").testDataType(TestDataType.DOUBLE).build())
                .addField(TestFieldSpec.builder().name("c_boolean").dataType("boolean").testDataType(TestDataType.BOOLEAN).build())
                .addField(TestFieldSpec.builder().name("c_date").dataType("date").testDataType(TestDataType.DATE).build())
                .addField(TestFieldSpec.builder().name("c_datetime").dataType("datetime").testDataType(TestDataType.DATETIME).build())
                .addField(TestFieldSpec.builder().name("c_timestamp").dataType("timestamp").testDataType(TestDataType.TIMESTAMP).build())
                .build();
    }

    /** 可选大文本字段（c_text）：AS400 等不支持 CLOB 的数据源不启用 */
    public static TestFieldSpec optionalTextField() {
        return TestFieldSpec.builder().name("c_text").dataType("text").testDataType(TestDataType.TEXT).build();
    }

    /** 可选二进制大对象字段（c_blob）：AS400 等不支持 BLOB 的数据源不启用 */
    public static TestFieldSpec optionalBlobField() {
        return TestFieldSpec.builder().name("c_blob").dataType("blob").testDataType(TestDataType.BLOB).build();
    }

    /**
     * 返回追加可选大对象字段（c_text/c_blob）后的新规格，原实例不变。
     * 支持 CLOB/BLOB 的数据源在 {@code createTestTableSpec()} 中调用启用。
     */
    public TestTableSpec withOptionalLargeObjectTypes() {
        Builder builder = builder().tableName(tableName).recordCount(recordCount);
        fields.forEach(builder::addField);
        builder.addField(optionalTextField()).addField(optionalBlobField());
        return builder.build();
    }

    /**
     * 随机表名：前缀 + 8 位 Base36 时间戳 + 8 位随机串。
     */
    public static String randomTableName(String prefix) {
        String ts = Long.toString(System.currentTimeMillis(), 36);
        String rand = Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
        String suffix = (ts + rand).toLowerCase();
        return prefix + suffix.substring(Math.max(0, suffix.length() - 16));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTableName() {
        return tableName;
    }

    public List<TestFieldSpec> getFields() {
        return fields;
    }

    public int getRecordCount() {
        return recordCount;
    }

    /** 主键字段列表（按声明顺序），无主键时返回空列表 */
    public List<TestFieldSpec> primaryKeyFields() {
        List<TestFieldSpec> pk = new ArrayList<>();
        for (TestFieldSpec field : fields) {
            if (field.isPrimaryKey()) {
                pk.add(field);
            }
        }
        return pk;
    }

    public static class Builder {
        private String tableName;
        private final List<TestFieldSpec> fields = new ArrayList<>();
        private int recordCount = 100;

        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public Builder addField(TestFieldSpec field) {
            this.fields.add(field);
            return this;
        }

        public Builder recordCount(int recordCount) {
            this.recordCount = recordCount;
            return this;
        }

        public TestTableSpec build() {
            if (tableName == null || tableName.isEmpty()) {
                throw new IllegalArgumentException("table name must not be empty");
            }
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("table must have at least one field");
            }
            return new TestTableSpec(this);
        }
    }
}
