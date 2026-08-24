package io.tapdata.it.mapping;

import io.tapdata.entity.schema.value.TapArrayValue;
import io.tapdata.entity.schema.value.TapBinaryValue;
import io.tapdata.entity.schema.value.TapBooleanValue;
import io.tapdata.entity.schema.value.TapDateValue;
import io.tapdata.entity.schema.value.TapDateTimeValue;
import io.tapdata.entity.schema.value.TapMapValue;
import io.tapdata.entity.schema.value.TapNumberValue;
import io.tapdata.entity.schema.value.TapRawValue;
import io.tapdata.entity.schema.value.TapStringValue;
import io.tapdata.entity.schema.value.TapValue;
import io.tapdata.it.schema.TestDataType;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 期望 TapValue 类型解析器：按 {@link TestDataType} 给出 wrap 后应得到的 TapValue 类集合。
 * <p>
 * 依据 {@code TapCodecsFilterManager.transformToTapValueMap} 的引擎契约推导：
 * <ul>
 *   <li>schema tapType 有专用 codec（DATE/DATETIME/TIME/ARRAY/MAP/YEAR/BINARY）→ 走类型专用 codec
 *       （TapDateValue/TapDateTimeValue/TapBinaryValue/TapMapValue/TapArrayValue 等）；</li>
 *   <li>类型专用 codec 对值不识别（如 {@code LocalDate} 不被 AnyTimeToDateTime 支持）→ 回落按值类型查默认
 *       codec（仅 Date/DateTime 有）→ 再失败 → {@code TapRawValue} 兜底（值保真、类型不识别），
 *       连接器可在 registerCapabilities 注册自定义 codec 升级为识别类型；</li>
 *   <li>其余 schema 类型（NUMBER/STRING/BOOLEAN 等无专用 codec）→ <b>引擎不包装，值原样保留</b>
 *       （参见 {@link #wrapsByDefault(TestDataType)}）；仅当连接器注册了该值类型的自定义 codec 时才被包装。</li>
 * </ul>
 * 期望类集合 = “引擎原生契约” + “连接器自定义 codec 可升级到的合法类”，
 * 仅当 wrap 结果确实为 TapValue 时断言实际类 ∈ 期望集合。
 * 集合中第一个元素为引擎原生契约（无连接器自定义 codec 时的稳定结果）。
 */
public final class TapValueClassResolver {

    private TapValueClassResolver() {
    }

    /**
     * 生成值（{@code RandomDataFactory} 产物：Integer/Long/BigDecimal/Float/Double/String/byte[]/
     * Boolean/LocalDate/LocalDateTime）被引擎包装时的期望 TapValue 类集合。
     * <p>
     * 注意：NUMBER/STRING/BOOLEAN 类型引擎默认不包装（保持原值），这里的集合表示
     * “连接器注册自定义 codec 后包装结果应属于的合法类”，避免自定义 codec 注册后误报。
     */
    public static Set<Class<? extends TapValue<?, ?>>> expectedClassesForGenerated(TestDataType type) {
        switch (type) {
            case INT:
            case BIGINT:
            case FLOAT:
            case DOUBLE:
            case DECIMAL:
                // 引擎默认不包装；连接器注册 Number 系 codec 后合法包装类为 TapNumberValue（BigDecimal 也可 TapRawValue）
                return type == TestDataType.DECIMAL
                        ? classes(TapNumberValue.class, TapRawValue.class)
                        : classes(TapNumberValue.class);
            case VARCHAR:
            case TEXT:
                return classes(TapStringValue.class);
            case BLOB:
                // byte[] → TapBinary 专用 codec → TapBinaryValue（稳定）
                return classes(TapBinaryValue.class);
            case BOOLEAN:
                return classes(TapBooleanValue.class);
            case DATE:
                // LocalDate 不被专用 codec 识别 → TapRawValue 兜底（引擎原生契约）；
                // 连接器注册 LocalDate codec 后可升级为 TapDateValue/TapDateTimeValue
                return classes(TapRawValue.class, TapDateValue.class, TapDateTimeValue.class);
            case DATETIME:
            case TIMESTAMP:
                // LocalDateTime → AnyTimeToDateTime 支持 → TapDateTimeValue（稳定）
                return classes(TapDateTimeValue.class);
            case MAP:
                return classes(TapMapValue.class);
            case ARRAY:
                return classes(TapArrayValue.class);
            default:
                return classes(TapRawValue.class);
        }
    }

    /**
     * 引擎默认 wrap 契约：该类型字段 wrap 后是否会被包装为 TapValue。
     * <p>
     * 仅 schema tapType 有专用 codec 的类型（DATE/DATETIME/TIME/ARRAY/MAP/YEAR/BINARY）会被包装；
     * 其余（NUMBER/STRING/BOOLEAN）引擎不包装、值原样保留 —— 即使值类型无默认 codec（如 BigDecimal）
     * 也不会触发 TapRawValue 兜底（该兜底只在类型专用 codec 已介入但失败时发生）。
     */
    public static boolean wrapsByDefault(TestDataType type) {
        switch (type) {
            case BLOB:
            case DATE:
            case DATETIME:
            case TIMESTAMP:
            case MAP:
            case ARRAY:
                return true;
            default:
                return false;
        }
    }

    /**
     * 读回值（batchRead 产物：Date/Timestamp/BigDecimal/String 等数据库回读类型）被引擎包装时的期望 TapValue 类集合。
     * <p>
     * 与 {@link #expectedClassesForGenerated(TestDataType)} 的差异：数据库读回的时间值（java.sql.Date/Timestamp）
     * 可被 TapDate/TapDateTime 专用 codec 识别（稳定得到 TapDateValue/TapDateTimeValue）；
     * 但部分连接器/驱动可能以字符串或原始类型返回时间列，故时间族保留 TapStringValue/TapRawValue 的合法兜底。
     * 其余类型同生成值：引擎默认不包装，仅当连接器注册自定义 codec 时才包装。
     */
    public static Set<Class<? extends TapValue<?, ?>>> expectedClassesForReadBack(TestDataType type) {
        switch (type) {
            case DATE:
                // 读回 java.sql.Date → TapDateValue；字符串/其他 → TapStringValue/TapRawValue 兜底
                return classes(TapDateValue.class, TapDateTimeValue.class, TapStringValue.class, TapRawValue.class);
            case DATETIME:
            case TIMESTAMP:
                // 读回 Timestamp/Date → TapDateTimeValue；字符串 → TapStringValue/TapRawValue 兜底
                return classes(TapDateTimeValue.class, TapDateValue.class, TapStringValue.class, TapRawValue.class);
            case BOOLEAN:
                // 部分库 boolean 以 tinyint(1)/bit 承载，读回 Integer → 引擎不包装（保持原值）；
                // 连接器注册 Boolean/Number codec 时合法包装类为 TapBooleanValue/TapNumberValue
                return classes(TapBooleanValue.class, TapNumberValue.class, TapRawValue.class);
            default:
                return expectedClassesForGenerated(type);
        }
    }

    @SafeVarargs
    private static Set<Class<? extends TapValue<?, ?>>> classes(Class<? extends TapValue<?, ?>>... expected) {
        return new LinkedHashSet<>(Arrays.asList(expected));
    }
}
