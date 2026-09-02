package io.tapdata.it.generator;

import io.tapdata.it.schema.TestFieldSpec;

import java.util.Random;

/** 布尔生成器：true/false 等概率（兼容 bit(1)/number(1) 存储） */
public class BoolGenerator extends BaseGenerator<Boolean> {

    public BoolGenerator(TestFieldSpec fieldSpec) {
        super(fieldSpec);
    }

    public BoolGenerator(TestFieldSpec fieldSpec, Random random) {
        super(fieldSpec, random);
    }

    @Override
    protected Boolean randomValue() {
        return nextBoolean();
    }
}
