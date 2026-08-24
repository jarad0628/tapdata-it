package io.tapdata.it.generator;

import io.tapdata.it.schema.TestFieldSpec;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 序列生成器：{@link AtomicLong} 从 1（或指定起点）递增。
 * 主键专用：保证 PK 唯一、可预测（1..N），update/delete 用例可精确指定目标行。
 */
public class SequenceGenerator extends BaseGenerator<Long> {

    private final AtomicLong sequence;

    public SequenceGenerator(TestFieldSpec fieldSpec) {
        this(fieldSpec, 1, null);
    }

    public SequenceGenerator(TestFieldSpec fieldSpec, long start) {
        this(fieldSpec, start, null);
    }

    public SequenceGenerator(TestFieldSpec fieldSpec, long start, Random random) {
        super(fieldSpec, random);
        this.sequence = new AtomicLong(start);
    }

    @Override
    protected Long randomValue() {
        return sequence.getAndIncrement();
    }
}
