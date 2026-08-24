package io.tapdata.it.generator;

import io.tapdata.it.schema.TestFieldSpec;

import java.util.Random;

/** 32 位整数生成器：默认值域 1 ~ 1,000,000 */
public class IntGenerator extends BaseGenerator<Integer> {

    public IntGenerator(TestFieldSpec fieldSpec) {
        super(fieldSpec);
    }

    public IntGenerator(TestFieldSpec fieldSpec, Random random) {
        super(fieldSpec, random);
    }

    @Override
    protected long defaultMaxBound(TestFieldSpec fieldSpec) {
        return 1_000_000;
    }

    @Override
    protected Integer randomValue() {
        return nextInt((int) minBound, (int) maxBound + 1);
    }
}
