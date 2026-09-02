package io.tapdata.it.generator;

import io.tapdata.it.schema.TestFieldSpec;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Random;

/**
 * 日期时间生成器：2020-01-01 ~ 当前时间（UTC 基准，保留毫秒小数）。
 * 统一 UTC 基准避免时区偏移导致的断言失败；
 * 毫秒精度数据写入秒精度列时由数据库按自身规则处理，断言侧按数据库行为对齐（见 RecordAssert 舍入比较）。
 */
public class DateTimeGenerator extends BaseGenerator<LocalDateTime> {

    private static final long START_MILLIS = LocalDateTime.of(2020, 1, 1, 0, 0, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli();

    public DateTimeGenerator(TestFieldSpec fieldSpec) {
        super(fieldSpec);
    }

    public DateTimeGenerator(TestFieldSpec fieldSpec, Random random) {
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
