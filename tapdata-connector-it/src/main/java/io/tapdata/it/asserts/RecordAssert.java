package io.tapdata.it.asserts;

import io.tapdata.it.schema.TestDataType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;

import org.junit.jupiter.api.Assertions;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 记录级数据断言：按列类型分派比较，容忍数据库存储/回读带来的类型等价差异。
 * <ul>
 *   <li>数值族：{@link BigDecimal} 归一化比较；float/double 允许相对误差 1e-6；</li>
 *   <li>布尔族：兼容 true/1/1.0 与 false/0/0.0（部分库 boolean 以 NUMBER(1)/bit(1) 存储）；</li>
 *   <li>日期族：LocalDate/LocalDateTime/ZonedDateTime/Timestamp/Date 统一转 LocalDateTime(UTC) 比较，
 *       容忍毫秒精度差异（fraction 不一致时截断到秒）；</li>
 *   <li>字符串族：去尾部空白后比较（char/text 存储差异）。</li>
 * </ul>
 */
public class RecordAssert {

    /** float/double 相对误差阈值 */
    public static final double RELATIVE_ERROR = 1e-6;

    private RecordAssert() {
    }

    /**
     * 断言单列值一致；失败信息包含列名、期望值、实际值、类型。
     */
    public static void assertEquals(TestDataType type, Object expected, Object actual, String columnName) {
        if (expected == null || actual == null) {
            Assertions.assertEquals(expected, actual, "column[" + columnName + "] null mismatch");
            return;
        }
        switch (type) {
            case INT:
            case BIGINT:
            case DECIMAL:
                assertNumberEquals(expected, actual, columnName);
                break;
            case FLOAT:
            case DOUBLE:
                assertFloatingEquals(expected, actual, columnName);
                break;
            case BOOLEAN:
                assertBooleanEquals(expected, actual, columnName);
                break;
            case DATE:
                assertDateEquals(expected, actual, columnName);
                break;
            case DATETIME:
            case TIMESTAMP:
                assertDateTimeEquals(expected, actual, columnName);
                break;
            case VARCHAR:
            case TEXT:
                assertStringEquals(expected, actual, columnName);
                break;
            default:
                Assertions.assertEquals(expected, actual, "column[" + columnName + "] mismatch");
        }
    }

    private static void assertNumberEquals(Object expected, Object actual, String columnName) {
        BigDecimal exp = toBigDecimal(expected);
        BigDecimal act = toBigDecimal(actual);
        if (exp == null || act == null) {
            Assertions.assertEquals(expected, actual, "column[" + columnName + "] number mismatch");
            return;
        }
        // decimal 固定 scale=4，归一化到 4 位后比较
        Assertions.assertEquals(exp.setScale(4, RoundingMode.HALF_UP),
                act.setScale(4, RoundingMode.HALF_UP),
                "column[" + columnName + "] number mismatch, expected=" + expected + ", actual=" + actual);
    }

    private static void assertFloatingEquals(Object expected, Object actual, String columnName) {
        Double exp = toDouble(expected);
        Double act = toDouble(actual);
        if (exp == null || act == null) {
            Assertions.assertEquals(expected, actual, "column[" + columnName + "] floating mismatch");
            return;
        }
        double diff = Math.abs(exp - act);
        double tolerance = RELATIVE_ERROR * Math.max(1.0, Math.max(Math.abs(exp), Math.abs(act)));
        assertTrue(diff <= tolerance,
                "column[" + columnName + "] floating mismatch, expected=" + expected + ", actual=" + actual
                        + ", diff=" + diff + ", tolerance=" + tolerance);
    }

    private static void assertBooleanEquals(Object expected, Object actual, String columnName) {
        Boolean exp = toBoolean(expected);
        Boolean act = toBoolean(actual);
        if (exp == null || act == null) {
            Assertions.assertEquals(expected, actual, "column[" + columnName + "] boolean mismatch");
            return;
        }
        Assertions.assertEquals(exp, act, "column[" + columnName + "] boolean mismatch, expected=" + expected + ", actual=" + actual);
    }

    private static void assertDateEquals(Object expected, Object actual, String columnName) {
        LocalDate exp = toLocalDate(expected);
        LocalDate act = toLocalDate(actual);
        if (exp == null || act == null) {
            Assertions.assertEquals(String.valueOf(expected), String.valueOf(actual),
                    "column[" + columnName + "] date mismatch, expected=" + expected + ", actual=" + actual);
            return;
        }
        Assertions.assertEquals(exp, act, "column[" + columnName + "] date mismatch, expected=" + expected + ", actual=" + actual);
    }

    private static void assertDateTimeEquals(Object expected, Object actual, String columnName) {
        LocalDateTime exp = toLocalDateTime(expected);
        LocalDateTime act = toLocalDateTime(actual);
        if (exp == null || act == null) {
            Assertions.assertEquals(String.valueOf(expected), String.valueOf(actual),
                    "column[" + columnName + "] datetime mismatch, expected=" + expected + ", actual=" + actual);
            return;
        }
        // 容忍毫秒精度差异：比较前舍入到秒（与 MySQL 对秒精度列的小数秒舍入存储行为一致，
        // 生成器保留毫秒值写入 timestamp(0) 列时 27.794 被存为 28，截断比较会误报差 1 秒）
        LocalDateTime expSec = roundToSecond(exp);
        LocalDateTime actSec = roundToSecond(act);
        Assertions.assertEquals(expSec, actSec,
                "column[" + columnName + "] datetime mismatch, expected=" + expected + ", actual=" + actual);
    }

    /** 四舍五入到秒（nano >= 0.5s 进 1 秒，跨日/月/年边界由 LocalDateTime 自动处理） */
    private static LocalDateTime roundToSecond(LocalDateTime value) {
        if (value.getNano() >= 500_000_000) {
            return value.plusSeconds(1).withNano(0);
        }
        return value.withNano(0);
    }

    private static void assertStringEquals(Object expected, Object actual, String columnName) {
        String exp = String.valueOf(expected).trim();
        String act = String.valueOf(actual).trim();
        Assertions.assertEquals(exp, act, "column[" + columnName + "] string mismatch, expected=" + expected + ", actual=" + actual);
    }

    // ===================== 类型归一化工具 =====================

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        String s = String.valueOf(value).trim();
        if ("1".equals(s) || "true".equalsIgnoreCase(s)) {
            return true;
        }
        if ("0".equals(s) || "false".equalsIgnoreCase(s)) {
            return false;
        }
        return null;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toLocalDate();
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate();
        }
        if (value instanceof Date) {
            return Instant.ofEpochMilli(((Date) value).getTime()).atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof Instant) {
            return ((Instant) value).atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof Number) {
            return LocalDate.ofEpochDay(((Number) value).longValue());
        }
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof LocalDate) {
            return ((LocalDate) value).atStartOfDay();
        }
        if (value instanceof ZonedDateTime) {
            return ((ZonedDateTime) value).toLocalDateTime();
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate().atStartOfDay();
        }
        if (value instanceof Date) {
            return Instant.ofEpochMilli(((Date) value).getTime()).atZone(ZoneOffset.UTC).toLocalDateTime();
        }
        if (value instanceof Instant) {
            return ((Instant) value).atZone(ZoneOffset.UTC).toLocalDateTime();
        }
        try {
            String s = String.valueOf(value).replace(' ', 'T');
            // MySQL batchRead 可能返回 ISO 字符串带 Z（UTC 标记），LocalDateTime 不能直接解析
            if (s.endsWith("Z")) {
                s = s.substring(0, s.length() - 1);
            }
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
