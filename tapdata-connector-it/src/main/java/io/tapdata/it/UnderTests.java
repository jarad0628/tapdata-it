package io.tapdata.it;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link UnderTest} 可重复标注的容器注解（Java 8 {@link java.lang.annotation.Repeatable} 要求）。
 * 使用方直接重复标注 {@code @UnderTest(...)} 即可，无需显式使用本容器注解。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface UnderTests {

    UnderTest[] value();
}
