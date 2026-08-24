package io.tapdata.it.generator;

import io.tapdata.it.schema.TestFieldSpec;

import java.util.Random;

/** 大文本生成器：256~1024 字符（含空格/标点/多字节），覆盖 text 类型 */
public class TextGenerator extends BaseGenerator<String> {

    private static final char[] CHARS = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            + " ，。！？；：、（）【】《》“”‘’·…—,.!?;:()[]{}<> 中文测试文本").toCharArray();

    public TextGenerator(TestFieldSpec fieldSpec) {
        super(fieldSpec);
    }

    public TextGenerator(TestFieldSpec fieldSpec, Random random) {
        super(fieldSpec, random);
    }

    @Override
    protected long defaultMinBound(TestFieldSpec fieldSpec) {
        return 256;
    }

    @Override
    protected long defaultMaxBound(TestFieldSpec fieldSpec) {
        return 1024;
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
