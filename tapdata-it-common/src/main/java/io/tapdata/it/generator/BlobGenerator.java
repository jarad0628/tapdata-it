package io.tapdata.it.generator;

import io.tapdata.it.schema.TestFieldSpec;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/** 二进制大对象生成器：256~1024 字节随机字节数组，覆盖 blob 类型 */
public class BlobGenerator extends BaseGenerator<byte[]> {

    public BlobGenerator(TestFieldSpec fieldSpec) {
        super(fieldSpec);
    }

    public BlobGenerator(TestFieldSpec fieldSpec, Random random) {
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
    protected byte[] randomValue() {
        int length = nextInt((int) minBound, (int) maxBound + 1);
        byte[] bytes = new byte[length];
        if (random != null) {
            random.nextBytes(bytes);
        } else {
            ThreadLocalRandom.current().nextBytes(bytes);
        }
        return bytes;
    }
}
