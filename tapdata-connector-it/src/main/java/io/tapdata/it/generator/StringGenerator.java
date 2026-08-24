package io.tapdata.it.generator;

import io.tapdata.it.schema.TestFieldSpec;

import java.util.Random;

/** 字符串生成器：16~32 位字母数字串，与 varchar(255) 兼容 */
public class StringGenerator extends BaseGenerator<String> {

    private static final char[] CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    public StringGenerator(TestFieldSpec fieldSpec) {
        super(fieldSpec);
    }

    public StringGenerator(TestFieldSpec fieldSpec, Random random) {
        super(fieldSpec, random);
    }

    @Override
    protected long defaultMinBound(TestFieldSpec fieldSpec) {
        return 16;
    }

    @Override
    protected long defaultMaxBound(TestFieldSpec fieldSpec) {
        return 32;
    }

    @Override
    protected String randomValue() {
        int length = nextInt((int) minBound, (int) maxBound + 1);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS[nextInt(0, CHARS.length)]);
        }
        return sb.toString();
    }
}
