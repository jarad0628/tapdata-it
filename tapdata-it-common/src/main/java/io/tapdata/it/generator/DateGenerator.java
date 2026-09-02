package io.tapdata.it.generator;

import io.tapdata.it.schema.TestFieldSpec;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Random;

/** 日期生成器：2020-01-01 ~ 当前日期（避免 0000-00-00 等非法值） */
public class DateGenerator extends BaseGenerator<LocalDate> {

    private static final LocalDate START_DATE = LocalDate.of(2020, 1, 1);

    public DateGenerator(TestFieldSpec fieldSpec) {
        super(fieldSpec);
    }

    public DateGenerator(TestFieldSpec fieldSpec, Random random) {
        super(fieldSpec, random);
    }

    @Override
    protected long defaultMinBound(TestFieldSpec fieldSpec) {
        return 0;
    }

    @Override
    protected long defaultMaxBound(TestFieldSpec fieldSpec) {
        return ChronoUnit.DAYS.between(START_DATE, LocalDate.now());
    }

    @Override
    protected LocalDate randomValue() {
        return START_DATE.plusDays(nextLong(minBound, maxBound + 1));
    }
}
