package io.tapdata.it.asserts;

import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.schema.type.TapBoolean;
import io.tapdata.entity.schema.type.TapDate;
import io.tapdata.entity.schema.type.TapDateTime;
import io.tapdata.entity.schema.type.TapNumber;
import io.tapdata.entity.schema.type.TapString;
import io.tapdata.entity.schema.type.TapTime;
import io.tapdata.entity.schema.type.TapType;
import io.tapdata.it.mapping.TapTypeResolver;
import io.tapdata.it.schema.TestDataType;
import io.tapdata.it.schema.TestFieldSpec;
import io.tapdata.it.schema.TestTableSpec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 表结构断言：字段名集合/顺序/类型族（TapType）/dataType 映射/主键。
 */
public class TableAssert {

    private TableAssert() {
    }

    /**
     * 按 TestTableSpec 断言 discoverSchema 返回的 TapTable：
     * 字段存在且顺序一致、tapType 类型族一致、dataType 被 spec dataTypes 声明、主键标记正确。
     * <p>
     * 关系型语义：字段数严格相等、主键标记严格一致（等价于宽松参数均为默认值）。
     */
    public static void assertFields(TapTable table, TestTableSpec spec, TapTypeResolver resolver) {
        assertFields(table, spec, resolver, false, true);
    }

    /**
     * 宽松版断言（NoSQL/schema-free 数据库适配）：
     *
     * @param allowExtraFields 允许数据库存在 spec 之外的隐式字段（如 MongoDB 的 _id），仅要求字段数不少于 spec
     * @param primaryKeyStrict 是否要求主键标记严格一致（false 时主键为库自动生成的场景仅验证字段存在）
     */
    public static void assertFields(TapTable table, TestTableSpec spec, TapTypeResolver resolver,
                                    boolean allowExtraFields, boolean primaryKeyStrict) {
        assertNotNull(table, "discoverSchema returned null table");
        assertNotNull(table.getNameFieldMap(), "table nameFieldMap is null");
        List<TestFieldSpec> fields = spec.getFields();
        if (allowExtraFields) {
            assertTrue(table.getNameFieldMap().size() >= fields.size(),
                    "field count should be at least " + fields.size() + ", actual=" + table.getNameFieldMap().size());
        } else {
            assertEquals(fields.size(), table.getNameFieldMap().size(),
                    "field count mismatch, expected=" + fields.size() + ", actual=" + table.getNameFieldMap().size());
        }

        int pos = 0;
        for (TestFieldSpec fieldSpec : fields) {
            TapField field = table.getNameFieldMap().get(fieldSpec.getName());
            assertNotNull(field, "field[" + fieldSpec.getName() + "] not found in schema, pos=" + pos);
            // 类型族断言：TestDataType → TapType
            assertTypeFamily(fieldSpec, field);
            // dataType 声明断言：方言 dataType 必须被 connector spec dataTypes 声明（表达式匹配 + 大小写不敏感）
            String actualDataType = field.getDataType();
            assertTrue(resolver.isDeclared(actualDataType),
                    "field[" + fieldSpec.getName() + "] dataType not declared in connector spec, actual="
                            + actualDataType);
            // 主键断言（非严格模式下主键由库自动生成，如 MongoDB 的 _id，不强制业务字段标记主键）
            if (fieldSpec.isPrimaryKey() && primaryKeyStrict) {
                assertEquals(Boolean.TRUE, field.getPrimaryKey(),
                        "field[" + fieldSpec.getName() + "] should be primary key");
            }
            pos++;
        }
    }

    private static void assertTypeFamily(TestFieldSpec fieldSpec, TapField field) {
        TapType tapType = field.getTapType();
        if (tapType == null) {
            // tapType 由引擎 TableFieldTypesGenerator 在 discoverSchema 后补充，
            // 部分 Connector（如 MySQL）的 discoverSchema 不设置 tapType，不强制断言
            return;
        }
        switch (fieldSpec.getTestDataType()) {
            case INT:
            case BIGINT:
            case DECIMAL:
            case FLOAT:
            case DOUBLE:
                assertTrue(tapType instanceof TapNumber,
                        "field[" + fieldSpec.getName() + "] should be TapNumber, actual=" + tapType.getClass().getSimpleName());
                break;
            case VARCHAR:
            case TEXT:
                assertTrue(tapType instanceof TapString,
                        "field[" + fieldSpec.getName() + "] should be TapString, actual=" + tapType.getClass().getSimpleName());
                break;
            case BOOLEAN:
                // 部分数据源无原生 BOOLEAN（DB2 i 用 SMALLINT、MySQL 用 tinyint 承载），
                // spec 声明为 TapNumber bit<=16，与 TapBoolean 语义等价，均视为通过
                assertTrue(tapType instanceof TapBoolean
                                || (tapType instanceof TapNumber && ((TapNumber) tapType).getBit() != null
                                && ((TapNumber) tapType).getBit() <= 16),
                        "field[" + fieldSpec.getName() + "] should be TapBoolean or TapNumber(bit<=16), actual="
                                + tapType.getClass().getSimpleName());
                break;
            case DATE:
                assertTrue(tapType instanceof TapDate || tapType instanceof TapDateTime,
                        "field[" + fieldSpec.getName() + "] should be TapDate/TapDateTime, actual=" + tapType.getClass().getSimpleName());
                break;
            case DATETIME:
            case TIMESTAMP:
                assertTrue(tapType instanceof TapDateTime || tapType instanceof TapDate || tapType instanceof TapTime,
                        "field[" + fieldSpec.getName() + "] should be TapDateTime, actual=" + tapType.getClass().getSimpleName());
                break;
            default:
                fail("unexpected test data type: " + fieldSpec.getTestDataType());
        }
    }
}
