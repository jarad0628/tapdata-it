package io.tapdata.it.schema;

/**
 * 通用测试数据类型。
 * <p>
 * 与 PDK {@code TapType} 一一对应，是数据生成器选择与类型断言（{@link io.tapdata.it.mapping.TapTypeResolver}，
 * 基于 connector spec.json dataTypes 自动解析）的依据。
 * 所有 Connector 均以该枚举声明测试字段语义，具体方言类型由 spec dataTypes 声明。
 */
public enum TestDataType {

    /** 32 位整数（对应 TapNumber 32bit） */
    INT,
    /** 64 位整数（对应 TapNumber 64bit） */
    BIGINT,
    /** 变长字符串（对应 TapString） */
    VARCHAR,
    /** 大文本（对应 TapString bytes 大） */
    TEXT,
    /** 二进制大对象（对应 TapBinary，可选类型：AS400 等数据源不支持 BLOB） */
    BLOB,
    /** 定点数（对应 TapNumber fixed） */
    DECIMAL,
    /** 32 位浮点（对应 TapNumber 浮点 32bit） */
    FLOAT,
    /** 64 位浮点（对应 TapNumber 浮点 64bit） */
    DOUBLE,
    /** 布尔（对应 TapBoolean） */
    BOOLEAN,
    /** 日期（对应 TapDate） */
    DATE,
    /** 日期时间（对应 TapDateTime） */
    DATETIME,
    /** 时间戳（对应 TapDateTime 含小数秒） */
    TIMESTAMP,
    /** 嵌套对象（对应 TapMap，仅用于转换契约用例，不落库） */
    MAP,
    /** 嵌套数组（对应 TapArray，仅用于转换契约用例，不落库） */
    ARRAY,
    /** 主键序列（不落库，仅用于生成器） */
    SEQUENCE
}
