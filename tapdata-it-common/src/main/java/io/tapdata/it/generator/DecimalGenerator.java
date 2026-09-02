package io.tapdata.it.generator;

import io.tapdata.it.schema.TestFieldSpec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

/** 定点数生成器：默认 1 ~ 10^8，固定 4 位小数（规避浮点/定点精度断言陷阱） */
public class DecimalGenerator extends BaseGenerator<BigDecimal> {

    private static final int SCALE = 4;

    public DecimalGenerator(TestFieldSpec fieldSpec) {
        super(fieldSpec);
    }

    public DecimalGenerator(TestFieldSpec fieldSpec, Random random) {
        super(fieldSpec, random);
    }

    @Override
    protected long defaultMaxBound(TestFieldSpec fieldSpec) {
        return 100_000_000L;
    }

    @Override
    protected BigDecimal randomValue() {
        double value = nextDouble((double) minBound, (double) maxBound);
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
