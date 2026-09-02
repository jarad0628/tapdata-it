package io.tapdata.it.generator;

/**
 * 生成器统一接口：一个生成器对应测试表的一列。
 *
 * @param <T> 生成值的类型
 */
public interface ValueGenerator<T> {

    /** 生成下一个值 */
    T next();

    /** 关联字段名 */
    String getColumnName();
}
