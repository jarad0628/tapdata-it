package io.tapdata.it.generator;

import io.tapdata.it.schema.TestFieldSpec;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 生成器抽象基类：统一随机值/固定值双模式与默认值域。
 * <p>
 * 默认使用 {@link ThreadLocalRandom}（线程安全、无锁竞争，支持并发生成）；
 * 传入显式 {@link Random}（同一种子）时全部生成器共享该随机源，可复现同一数据序列。
 *
 * @param <T> 生成值的类型
 */
public abstract class BaseGenerator<T> implements ValueGenerator<T> {

    protected final String columnName;
    /** true=随机值模式；false=固定值模式（TestFieldSpec.fixedValue 注入） */
    protected final boolean isRandom;
    /** 随机下界（子类解释为数值/时间戳/长度等） */
    protected final long minBound;
    /** 随机上界 */
    protected final long maxBound;
    /** 固定值模式取值 */
    protected final T fixedValue;
    /** null 时使用 ThreadLocalRandom */
    protected final Random random;

    protected BaseGenerator(TestFieldSpec fieldSpec) {
        this(fieldSpec, null);
    }

    protected BaseGenerator(TestFieldSpec fieldSpec, Random random) {
        this.columnName = fieldSpec.getName();
        @SuppressWarnings("unchecked")
        T fixed = (T) fieldSpec.getFixedValue();
        this.fixedValue = fixed;
        this.isRandom = (fixedValue == null);
        this.minBound = defaultMinBound(fieldSpec);
        this.maxBound = defaultMaxBound(fieldSpec);
        this.random = random;
    }

    /** 默认随机下界：子类可覆写 */
    protected long defaultMinBound(TestFieldSpec fieldSpec) {
        return 1;
    }

    /** 默认随机上界：子类可覆写 */
    protected long defaultMaxBound(TestFieldSpec fieldSpec) {
        return 1_000_000;
    }

    /** 子类实现随机取值逻辑（isRandom=false 时不会被调用） */
    protected abstract T randomValue();

    /** 线程安全随机源：显式 Random 优先，否则 ThreadLocalRandom */
    protected long nextLong(long origin, long bound) {
        if (random != null) {
            return origin + (long) (random.nextDouble() * (bound - origin));
        }
        return ThreadLocalRandom.current().nextLong(origin, bound);
    }

    protected int nextInt(int origin, int bound) {
        if (random != null) {
            return origin + random.nextInt(bound - origin);
        }
        return ThreadLocalRandom.current().nextInt(origin, bound);
    }

    protected boolean nextBoolean() {
        if (random != null) {
            return random.nextBoolean();
        }
        return ThreadLocalRandom.current().nextBoolean();
    }

    protected double nextDouble(double origin, double bound) {
        if (random != null) {
            return origin + random.nextDouble() * (bound - origin);
        }
        return ThreadLocalRandom.current().nextDouble(origin, bound);
    }

    @Override
    public final T next() {
        return isRandom ? randomValue() : fixedValue;
    }

    @Override
    public String getColumnName() {
        return columnName;
    }
}
