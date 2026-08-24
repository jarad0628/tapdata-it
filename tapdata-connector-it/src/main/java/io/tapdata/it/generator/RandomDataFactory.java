package io.tapdata.it.generator;

import io.tapdata.it.schema.TestDataType;
import io.tapdata.it.schema.TestFieldSpec;
import io.tapdata.it.schema.TestTableSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 随机数据工厂：依据 {@link TestTableSpec} 为每列构建生成器，批量产出数据行。
 * <p>
 * 主键列自动替换为 {@link SequenceGenerator}（从 1 递增）；
 * 默认无种子（每次运行取值不同），支持显式种子复现同一数据序列。
 */
public class RandomDataFactory {

    private RandomDataFactory() {
    }

    /**
     * 为表规格构建每列生成器（主键列自动使用 SequenceGenerator）。
     */
    public static List<ValueGenerator<?>> createGenerators(TestTableSpec tableSpec) {
        return createGenerators(tableSpec, null);
    }

    /**
     * 带随机源的构建：种子模式下同一种子生成相同数据序列。
     */
    public static List<ValueGenerator<?>> createGenerators(TestTableSpec tableSpec, Random random) {
        List<ValueGenerator<?>> generators = new ArrayList<>(tableSpec.getFields().size());
        for (TestFieldSpec fieldSpec : tableSpec.getFields()) {
            if (fieldSpec.isPrimaryKey()) {
                generators.add(new SequenceGenerator(fieldSpec, 1, random));
            } else {
                generators.add(newGenerator(fieldSpec, random));
            }
        }
        return generators;
    }

    /** 依类型构建生成器；未知类型抛异常提示子类扩展 */
    public static ValueGenerator<?> newGenerator(TestFieldSpec fieldSpec, Random random) {
        switch (fieldSpec.getTestDataType()) {
            case INT:
                return new IntGenerator(fieldSpec, random);
            case BIGINT:
                return new LongGenerator(fieldSpec, random);
            case VARCHAR:
                return new StringGenerator(fieldSpec, random);
            case TEXT:
                return new TextGenerator(fieldSpec, random);
            case BLOB:
                return new BlobGenerator(fieldSpec, random);
            case DECIMAL:
                return new DecimalGenerator(fieldSpec, random);
            case FLOAT:
                return new FloatGenerator(fieldSpec, random);
            case DOUBLE:
                return new DoubleGenerator(fieldSpec, random);
            case BOOLEAN:
                return new BoolGenerator(fieldSpec, random);
            case DATE:
                return new DateGenerator(fieldSpec, random);
            case DATETIME:
                return new DateTimeGenerator(fieldSpec, random);
            case TIMESTAMP:
                return new TimestampGenerator(fieldSpec, random);
            case SEQUENCE:
                return new SequenceGenerator(fieldSpec, 1, random);
            default:
                throw new IllegalArgumentException("Unsupported test data type: " + fieldSpec.getTestDataType());
        }
    }

    /**
     * 生成一行数据：Map&lt;字段名, 值&gt;，按字段声明顺序遍历生成器。
     */
    public static Map<String, Object> nextRow(List<ValueGenerator<?>> generators) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (ValueGenerator<?> generator : generators) {
            row.put(generator.getColumnName(), generator.next());
        }
        return row;
    }

    /**
     * 批量生成 N 行（默认无种子），返回期望数据供写入后校验。
     */
    public static List<Map<String, Object>> generateRows(TestTableSpec tableSpec, int count) {
        return generateRows(tableSpec, count, null);
    }

    /**
     * 带种子批量生成（可复现）。
     */
    public static List<Map<String, Object>> generateRows(TestTableSpec tableSpec, int count, Random random) {
        List<ValueGenerator<?>> generators = createGenerators(tableSpec, random);
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(nextRow(generators));
        }
        return rows;
    }

    /**
     * 带种子批量生成（long 种子自动构造 {@link Random}）。
     */
    public static List<Map<String, Object>> generateRows(TestTableSpec tableSpec, int count, long seed) {
        return generateRows(tableSpec, count, new Random(seed));
    }

    /** 便于测试场景直接构造生成器（无字段规格时按类型默认值域） */
    public static ValueGenerator<?> newGenerator(TestDataType testDataType) {
        TestFieldSpec spec = TestFieldSpec.builder()
                .name("col").dataType(testDataType.name().toLowerCase()).testDataType(testDataType).build();
        return newGenerator(spec, null);
    }
}
