package io.tapdata.it;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明当前测试用例被测的 Connector function 能力（能力覆盖校验的输入之一）。
 * <p>
 * {@link #value()} 为 ConnectorFunctions 能力名（与 {@link ConnectorIT#require(java.util.function.Supplier, String)}
 * 的 capability 参数一致，如 "writeRecord" / "batchCount"）。ConnectorIT 汇总全部用例的
 * value() 得到“被测能力集合”，与被测 connector 的已实现能力集合比对：
 * <ul>
 *   <li>connector 已实现但无用例标注 → 校验用例失败（已实现能力必须被测试）</li>
 *   <li>connector 声明的必实现能力（{@link ConnectorIT#requiredCapabilities()}）未实现 →
 *       用例执行或校验用例失败（connector 不遗漏必实现接口）</li>
 * </ul>
 * 注意：必实现能力清单由被测 connector 子类通过 {@link ConnectorIT#requiredCapabilities()} 主动声明
 * （原则 3：声明式能力），本注解不承担必实现声明职责，仅声明“本用例测哪个能力”，
 * 供覆盖校验汇总与旁路验证器要求判定。
 * <p>
 * 一个用例可重复标注本注解声明多个被测能力（如事务用例同时被测 transactionBegin 与
 * transactionCommit）；{@link #requiresVerifier()} = true 声明该用例必须通过旁路验证器直连
 * 对端数据源验证（原则 1：禁止用 connector 的 read 验证 connector 的 write），
 * setUp 阶段验证器缺失（自动发现失败且子类未覆写 createVerifier）时用例直接失败。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(UnderTests.class)
public @interface UnderTest {

    /** 被测 Connector function 能力名（ConnectorFunctions getter 语义，如 "writeRecord"） */
    String value();

    /** 该用例是否依赖旁路验证器（数据类用例必须为 true） */
    boolean requiresVerifier() default false;
}
