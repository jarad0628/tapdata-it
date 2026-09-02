package io.tapdata.it.generator;

import io.tapdata.it.schema.TestFieldSpec;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Random;

/**
 * 时间戳生成器：同 DATETIME 值域，UTC 基准，保留毫秒小数（覆盖 fraction=3/6 精度差异场景）。
 * <p>
 * 毫秒精度数据写入秒精度列（如 MySQL timestamp(0)）时由数据库按自身规则处理
 * （MySQL 舍入到秒），断言侧按数据库行为对齐（见 RecordAssert 舍入比较）。
 */
public class TimestampGenerator extends BaseGenerator<LocalDateTime> {

    private static final long START_MILLIS = LocalDateTime.of(2020, 1, 1, 0, 0, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli();

    public TimestampGenerator(TestFieldSpec fieldSpec) {
        super(fieldSpec);
    }

    public TimestampGenerator(TestFieldSpec fieldSpec, Random random) {
        super(fieldSpec, random);
    }

    @Override
    protected long defaultMinBound(TestFieldSpec fieldSpec) {
        return 0;
    }

    @Override
    protected long defaultMaxBound(TestFieldSpec fieldSpec) {
        return System.currentTimeMillis() - START_MILLIS;
    }

    @Override
    protected LocalDateTime randomValue() {
        long millis = START_MILLIS + nextLong(minBound, maxBound + 1);
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }
}
