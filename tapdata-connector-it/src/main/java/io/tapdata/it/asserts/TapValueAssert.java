package io.tapdata.it.asserts;

import io.tapdata.entity.schema.value.ByteData;
import io.tapdata.entity.schema.value.DateTime;
import io.tapdata.entity.schema.value.TapRawValue;
import io.tapdata.entity.schema.value.TapValue;
import io.tapdata.it.schema.TestDataType;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TapValue 断言：wrap 结果的三类契约验证。
 * <ul>
 *   <li>类断言：wrap 后 TapValue 子类 ∈ 期望集合（{@link io.tapdata.it.mapping.TapValueClassResolver}）；</li>
 *   <li>值断言：wrap 后 value 与原值语义等价（容忍类型差异，与 {@link RecordAssert} 同源思路）；</li>
 *   <li>origin 断言：wrap 值被变换（value != 原值）时 originValue 保留原值，originType = schema dataType。</li>
 * </ul>
 * 时间族（DATE/DATETIME/TIMESTAMP）的 value 断言采用 epoch 毫秒等价：
 * {@link DateTime} 内部以 UTC 解释的 epoch 存储，与 LocalDate/LocalDateTime（UTC 解释）、
 * Date/Timestamp（getTime）比较时均以 epoch 为唯一参照，避免测试机时区导致“墙上时间”偏差。
 * 无法 epoch 化的值（非 ISO 时间文本）退化回 {@link RecordAssert} 文本比较。
 */
public final class TapValueAssert {

    private TapValueAssert() {
    }

    /** 断言 wrap 结果类 ∈ 期望集合（null 或集合不匹配时给出实际类与期望集合） */
    public static void assertValueClass(TestDataType type, TapValue<?, ?> tapValue,
                                        Set<Class<? extends TapValue<?, ?>>> expectedClasses, String fieldName) {
        assertNotNull(tapValue, "field[" + fieldName + "] should be wrapped into TapValue");
        for (Class<? extends TapValue<?, ?>> expected : expectedClasses) {
            if (expected.isInstance(tapValue)) {
                return;
            }
        }
        assertTrue(false, "field[" + fieldName + "] wrapped class " + tapValue.getClass().getSimpleName()
                + " not in expected " + expectedClasses + " for type " + type);
    }

    /** 断言 wrap 后的 value 与原值语义等价（分类型：BLOB 字节比较 / 嵌套递归 / 时间 epoch / 其余走 RecordAssert） */
    public static void assertValueEquals(TestDataType type, Object expectedPlain, TapValue<?, ?> tapValue, String fieldName) {
        assertNotNull(tapValue, "field[" + fieldName + "] should be wrapped into TapValue");
        assertValueEquals(type, expectedPlain, tapValue.getValue(), fieldName);
    }

    /** 断言普通值之间语义等价（unwrap 结果与原值比较；分派逻辑同 TapValue 版） */
    public static void assertValueEquals(TestDataType type, Object expectedPlain, Object actualPlain, String fieldName) {
        switch (type) {
            case BLOB:
                assertBinaryEquals(expectedPlain, actualPlain, fieldName);
                break;
            case MAP:
            case ARRAY:
                assertNestedEquals(expectedPlain, actualPlain, fieldName);
                break;
            case DATE:
            case DATETIME:
            case TIMESTAMP:
                assertTemporalEquals(type, expectedPlain, actualPlain, fieldName);
                break;
            default:
                RecordAssert.assertEquals(type, expectedPlain, actualPlain, fieldName);
        }
    }

    /**
     * 断言 origin 元数据契约：
     * <ul>
     *   <li>wrap 值被变换（value != 原值）且非 TapRawValue（Raw 的 value 即原值本身）→ originValue 必须保留原值；</li>
     *   <li>originType 恒等于 schema dataType（transformToTapValueMap 无条件设置）。</li>
     * </ul>
     */
    public static void assertOriginMetadata(TestDataType type, Object expectedPlain, TapValue<?, ?> tapValue,
                                            String expectedOriginType, String fieldName) {
        Object wrapped = tapValue.getValue();
        boolean valueChanged = expectedPlain != null && !expectedPlain.equals(wrapped);
        if (valueChanged && !(tapValue instanceof TapRawValue)) {
            assertNotNull(tapValue.getOriginValue(),
                    "field[" + fieldName + "] should preserve originValue when wrapped value differs"
                            + " (wrapped=" + wrapped + " vs original=" + expectedPlain + ")");
            RecordAssert.assertEquals(type, expectedPlain, tapValue.getOriginValue(), fieldName);
        }
        assertEquals(expectedOriginType, tapValue.getOriginType(),
                "field[" + fieldName + "] originType mismatch");
    }

    // ===================== 分类型断言 =====================

    private static void assertBinaryEquals(Object expected, Object actual, String fieldName) {
        byte[] exp = toBytes(expected);
        byte[] act = toBytes(actual);
        assertNotNull(exp, "field[" + fieldName + "] expected binary value missing");
        assertNotNull(act, "field[" + fieldName + "] wrapped binary value missing, got: " + actual);
        assertTrue(Arrays.equals(exp, act),
                "field[" + fieldName + "] binary mismatch, expected len=" + exp.length + ", actual len=" + act.length);
    }

    private static byte[] toBytes(Object value) {
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        if (value instanceof ByteData) {
            return ((ByteData) value).getValue();
        }
        return null;
    }

    private static void assertTemporalEquals(TestDataType type, Object expected, Object actual, String fieldName) {
        Long expMillis = toEpochMillis(expected);
        Long actMillis = toEpochMillis(actual);
        if (expMillis != null && actMillis != null) {
            assertEquals(expMillis, actMillis,
                    "field[" + fieldName + "] temporal mismatch (epoch millis), expected=" + expected + ", actual=" + actual);
            return;
        }
        // 字符串等无法 epoch 化的值退化为文本比较（RecordAssert 内部 parse 墙钟）
        RecordAssert.assertEquals(type, expected, actual, fieldName);
    }

    /**
     * 时间值 → epoch 毫秒（唯一时间参照系）：
     * DateTime/Instant 直接取 epoch；Date/Timestamp 取 getTime；LocalDate/LocalDateTime 按 UTC 解释
     * （与 {@code DateTime(LocalDateTime)} 构造器按 UTC 解释的引擎语义一致）。
     */
    private static Long toEpochMillis(Object value) {
        if (value instanceof DateTime) {
            return ((DateTime) value).toInstant().toEpochMilli();
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).getTime();
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).getTime();
        }
        if (value instanceof java.sql.Time) {
            return ((java.sql.Time) value).getTime();
        }
        if (value instanceof Date) {
            return ((Date) value).getTime();
        }
        if (value instanceof Instant) {
            return ((Instant) value).toEpochMilli();
        }
        if (value instanceof ZonedDateTime) {
            return ((ZonedDateTime) value).toInstant().toEpochMilli();
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
        }
        if (value instanceof LocalDate) {
            return ((LocalDate) value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        }
        if (value instanceof String) {
            return parseStringEpochMillis((String) value);
        }
        return null;
    }

    /**
     * 时间字符串 → epoch 毫秒：按 ISO 解析后统一按 JVM 本地时区解释
     * （与引擎 {@code AnyTimeToDateTime(String)} 的 SimpleDateFormat 本地时区语义一致，
     * 保证“连接器输出的时间字符串 → 引擎 wrap 回 DateTime”两侧参照系自洽），
     * 兼容 "yyyy-MM-dd"、"yyyy-MM-dd HH:mm:ss[.fraction]"（空格分隔）与带时区偏移的 ISO 格式。
     * 解析失败（如连接器输出的非时间文本）返回 null，交由调用方退化比较。
     */
    private static Long parseStringEpochMillis(String value) {
        String normalized = value.replace(' ', 'T');
        ZoneId zoneId = ZoneId.systemDefault();
        try {
            return LocalDateTime.parse(normalized).atZone(zoneId).toInstant().toEpochMilli();
        } catch (Exception ignore) {
        }
        try {
            return LocalDate.parse(normalized).atStartOfDay(zoneId).toInstant().toEpochMilli();
        } catch (Exception ignore) {
        }
        try {
            return OffsetDateTime.parse(normalized).toInstant().toEpochMilli();
        } catch (Exception ignore) {
        }
        return null;
    }

    /** 嵌套（Map/List）递归语义比较：数值统一 BigDecimal、时间统一 epoch，容忍 unwrap 后的类型漂移 */
    public static void assertNestedEquals(Object expected, Object actual, String fieldName) {
        if (expected instanceof Map && actual instanceof Map) {
            Map<?, ?> expMap = (Map<?, ?>) expected;
            Map<?, ?> actMap = (Map<?, ?>) actual;
            assertEquals(expMap.size(), actMap.size(),
                    "field[" + fieldName + "] nested map size mismatch, expected=" + expMap + ", actual=" + actMap);
            for (Object key : expMap.keySet()) {
                assertTrue(actMap.containsKey(key),
                        "field[" + fieldName + "] nested map missing key " + key + " in " + actMap);
                assertNestedEquals(expMap.get(key), actMap.get(key), fieldName + "." + key);
            }
            return;
        }
        if (expected instanceof Collection && actual instanceof Collection) {
            Collection<?> expCol = (Collection<?>) expected;
            Collection<?> actCol = (Collection<?>) actual;
            assertEquals(expCol.size(), actCol.size(),
                    "field[" + fieldName + "] nested collection size mismatch, expected=" + expCol + ", actual=" + actCol);
            Object[] expArr = expCol.toArray();
            Object[] actArr = actCol.toArray();
            for (int i = 0; i < expArr.length; i++) {
                assertNestedEquals(expArr[i], actArr[i], fieldName + "[" + i + "]");
            }
            return;
        }
        Object expScalar = normalizeScalar(expected);
        Object actScalar = normalizeScalar(actual);
        if (expScalar instanceof byte[] && actScalar instanceof byte[]) {
            assertTrue(Arrays.equals((byte[]) expScalar, (byte[]) actScalar),
                    "field[" + fieldName + "] nested binary mismatch");
            return;
        }
        assertEquals(expScalar, actScalar,
                "field[" + fieldName + "] nested value mismatch, expected=" + expected + ", actual=" + actual);
    }

    /** 标量归一化：TapValue 解包、Number → BigDecimal（对齐 RecordAssert 数值语义）、DateTime → epoch 毫秒 */
    private static Object normalizeScalar(Object value) {
        if (value instanceof TapValue) {
            return normalizeScalar(((TapValue<?, ?>) value).getValue());
        }
        if (value instanceof DateTime) {
            return ((DateTime) value).toInstant().toEpochMilli();
        }
        if (value instanceof ByteData) {
            return ((ByteData) value).getValue();
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        return value;
    }
}
