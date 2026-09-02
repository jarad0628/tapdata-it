package io.tapdata.it.generator;

import io.tapdata.it.schema.TestFieldSpec;

import java.util.Random;

/** 32 位浮点生成器：默认 1 ~ 10^4（保留 2 位小数时 ≤6 位有效数字，在 float 精确范围内）；断言时允许相对误差 */
public class FloatGenerator extends BaseGenerator<Float> {

    public FloatGenerator(TestFieldSpec fieldSpec) {
        super(fieldSpec);
    }

    public FloatGenerator(TestFieldSpec fieldSpec, Random random) {
        super(fieldSpec, random);
    }

    @Override
    protected long defaultMaxBound(TestFieldSpec fieldSpec) {
        return 10_000L;
    }

    @Override
    protected Float randomValue() {
        double value = nextDouble((double) minBound, (double) maxBound);
        return (float) (Math.round(value * 100.0) / 100.0);
    }
}
