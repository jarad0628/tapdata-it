package io.tapdata.it.generator;

import io.tapdata.it.schema.TestFieldSpec;

import java.util.Random;

/** 64 位整数生成器：默认值域 1 ~ 10^12 */
public class LongGenerator extends BaseGenerator<Long> {

    public LongGenerator(TestFieldSpec fieldSpec) {
        super(fieldSpec);
    }

    public LongGenerator(TestFieldSpec fieldSpec, Random random) {
        super(fieldSpec, random);
    }

    @Override
    protected long defaultMaxBound(TestFieldSpec fieldSpec) {
        return 1_000_000_000_000L;
    }

    @Override
    protected Long randomValue() {
        return nextLong(minBound, maxBound + 1);
    }
}
