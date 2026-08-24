package io.tapdata.it.generator;

import io.tapdata.it.schema.TestFieldSpec;

import java.util.Random;

/** 64 位浮点生成器：默认 1 ~ 10^12，保留 4 位小数；断言时允许相对误差 */
public class DoubleGenerator extends BaseGenerator<Double> {

    public DoubleGenerator(TestFieldSpec fieldSpec) {
        super(fieldSpec);
    }

    public DoubleGenerator(TestFieldSpec fieldSpec, Random random) {
        super(fieldSpec, random);
    }

    @Override
    protected long defaultMaxBound(TestFieldSpec fieldSpec) {
        return 1_000_000_000_000L;
    }

    @Override
    protected Double randomValue() {
        double value = nextDouble((double) minBound, (double) maxBound);
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
