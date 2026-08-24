# TapData Connector 通用集成测试框架设计

> 版本：v1.0
> 适用代码库：`tapdata`（社区版引擎）、`tapdata-connectors`（社区版连接器）、`tapdata-connectors-enterprise`（企业版连接器）
> 目标模块：`tapdata/tapdata-it`

## 1. 背景与目标

TapData 连接器生态包含 RDBMS（MySQL、Oracle、PostgreSQL 等）、NoSQL（MongoDB、Redis、Elasticsearch 等）、消息队列（Kafka、RocketMQ 等）等数十种 Connector。目前各连接器缺少统一的集成测试体系：测试用例散落、数据准备方式不统一、能力覆盖不完整，无法快速验证"连接器实现是否符合 PDK 契约"。

本框架提供一个**连接器通用集成测试基类** `ConnectorIT`，目标是：

1. **一份用例，全连接器复用**：所有 Connector 只需继承 `ConnectorIT` 并实现 `createContext()` 提供连接配置与已初始化的 Connector 实例，即可自动运行本类中定义的通用集成测试用例。
2. **能力驱动，自动跳过**：测试方法通过 `ConnectorFunctions` 动态检测被测连接器注册的能力，对不支持的能力使用 `assumeTrue` 自动跳过，避免误报。
3. **数据与表结构统一供给**：测试表名随机生成（避免依赖/污染被测环境既有数据），表结构与测试数据由基类统一提供，Connector 侧只需关心连接配置。
4. **通用性与可扩展性**：不同 Connector 连接不同数据源、支持的数据类型各不相同，通用用例必须易于扩展与定制（字段模型、数据类型映射、数据生成、断言均可覆写）。

## 2. 现状分析

### 2.1 任务执行引擎（iengine-app）

- 任务执行引擎位于 `tapdata/iengine/iengine-app/src/main/java/io/tapdata/flow/engine/V2`，核心入口为 `TaskService`（`io.tapdata.flow.engine.V2.task.TaskService`）。
- 引擎通过 PDK API 驱动 Connector：`TapConnector`（`tapdata-common-lib/plugin-kit/tapdata-pdk-api`）定义 Connector 生命周期与能力契约：
  - 生命周期：`init(TapConnectionContext)` → `onStart(TapConnectionContext)`（连接建立、资源初始化）→ 能力调用 → `stop(TapConnectionContext)`（资源释放）；
  - 能力契约：`registerCapabilities(ConnectorFunctions, TapCodecsRegistry)` 注册全部能力函数（建表、删表、读写、事务等）；
  - 元数据：`discoverSchema(TapConnectionContext, List<String> tables, int tableSize, Consumer<List<TapTable>>)`、`connectionTest(...)`。
- 引擎运行时把 `ConnectorFunctions` 中各函数作为任务执行的基本单元（全量读写、增量 CDC、DDL 同步、数据校验等），因此**集成测试直接调用 `ConnectorFunctions` 中各函数，等价于验证引擎侧任务执行路径的底层能力**，是引擎任务执行正确性的最小充分验证。

### 2.2 Connector 侧

- 社区版 Connector：`tapdata-connectors/connectors`（mysql、mongodb、postgres、oracle 等 50+ 模块），企业版：`tapdata-connectors-enterprise/connectors`（db2、dameng、gbase 等）。
- 每个 Connector 形如 `MysqlConnector extends CommonDbConnector implements TapConnector`，通过 `@TapConnectorClass("mysql-spec.json")` 标注规范文件（内含 `dataTypes` 数据类型映射），在 `registerCapabilities` 中注册能力。
- 现状：各 Connector 自带的测试以 Mockito 单元测试为主（如 `MysqlConnectorTest`），缺少真实数据源上的端到端能力验证；`tapdata-test` 模块的 `HotLoadConnectorTester` 提供 JAR 热加载性能测试，但非 JUnit 集成测试体系。
- `tapdata-it` 模块当前仅有骨架 `pom.xml`（parent `daas`），尚无代码。

### 2.3 随机数据生成器（详细设计）

随机数据生成器是框架数据准备与增量写入的核心组件，位于 `io.tapdata.it.generator` 包。它依据 `TestTableSpec` 的字段规格（`TestFieldSpec`）为每个字段建立对应生成器，批量产出**类型受控、可回读校验、可复现**的随机数据行。

#### 2.3.1 设计目标

1. **全类型覆盖**：int/bigint/varchar/text/decimal/float/double/boolean/date/datetime/timestamp 均有对应生成器，并支持子类扩展（binary/time/year/map/array 等）；
2. **值域受控**：所有随机值落在数据库类型安全范围内，避免数值溢出、非法日期、精度丢失；
3. **可复现**：支持随机种子（seed），同一种子生成相同数据序列，便于失败用例复现；
4. **主键保证**：主键列自动使用序列生成器，保证唯一性与确定性（update/delete 用例需按主键精确定位）；
5. **高性能**：全部使用 `ThreadLocalRandom`，无共享随机源竞争，支持多线程并发生成。

#### 2.3.2 接口与类层次

```java
/** 生成器统一接口：一个生成器对应一列 */
public interface ValueGenerator<T> {
    T next();                   // 生成下一个值
    String getColumnName();     // 关联字段名
}
```

```java
/** 抽象基类：统一随机边界与固定值双模式 */
public abstract class BaseGenerator<T> implements ValueGenerator<T> {
    protected final String columnName;
    protected final boolean isRandom;    // true=随机值模式；false=固定值模式（边界/定值注入）
    protected final long minBound;       // 随机下界（子类解释为数值/日期时间戳/长度等）
    protected final long maxBound;       // 随机上界
    protected final T fixedValue;        // 固定值模式取值（TestFieldSpec.fixedValue）

    protected BaseGenerator(TestFieldSpec fieldSpec) {
        this.columnName = fieldSpec.getName();
        this.fixedValue = fieldSpec.getFixedValue();
        this.isRandom = (fixedValue == null);
        this.minBound = defaultMinBound(fieldSpec);   // 依类型取默认边界
        this.maxBound = defaultMaxBound(fieldSpec);
    }

    /** 子类实现随机取值逻辑 */
    protected abstract T randomValue();

    @Override
    public final T next() {
        return isRandom ? randomValue() : fixedValue;
    }
}
```

#### 2.3.3 类型覆盖与默认值域

| TestDataType | 生成器 | 默认值域/策略 | 边界说明 |
|---|---|---|---|
| INT | `IntGenerator` | 1 ~ 1,000,000 | 覆盖 int 常用量级，避免溢出 |
| BIGINT | `LongGenerator` | 1 ~ 10^12 | 主键常用类型，见主键策略 |
| VARCHAR | `StringGenerator` | 16~32 位字母数字串 | 与 varchar(255) 兼容 |
| TEXT | `TextGenerator` | 256~1024 字符（含空格/标点/多字节） | 覆盖 text 类型与长字符串 |
| DECIMAL | `DecimalGenerator` | 1 ~ 10^8，固定 4 位小数 | 规避浮点/定点精度断言陷阱 |
| FLOAT | `FloatGenerator` | 1 ~ 10^6，2 位小数 | 断言允许相对误差 |
| DOUBLE | `DoubleGenerator` | 1 ~ 10^12，4 位小数 | 同上 |
| BOOLEAN | `BoolGenerator` | true/false 等概率 | 兼容 bit(1)/number(1) 存储 |
| DATE | `DateGenerator` | 2020-01-01 ~ 当前日期 | 避免 0000-00-00 等非法值 |
| DATETIME | `DateTimeGenerator` | 2020-01-01 ~ 当前时间（UTC） | 统一 UTC 基准，避免时区偏移 |
| TIMESTAMP | `TimestampGenerator` | 同 DATETIME，含毫秒小数 | 覆盖 fraction=3/6 精度差异 |
| SEQUENCE | `SequenceGenerator` | `AtomicLong` 从 1 递增 | 主键专用，见主键策略 |

#### 2.3.4 主键与确定性

- `RandomDataFactory.createGenerators` 自动识别 `primaryKey == true` 的字段并替换为 `SequenceGenerator`（从 1 递增）：
  - 写入数据主键唯一、可预测（1..N），update/delete 用例可精确指定目标行；
  - 多用例/多表间互不冲突，支持跨用例数据追加。
- 非主键列若存在唯一索引，可覆写 `createGenerators` 改为序列或固定值，避免随机冲突。

#### 2.3.5 固定值与边界注入

- `TestFieldSpec` 支持 `fixedValue`：设置后生成器进入固定值模式（`isRandom=false`），用于边界测试（int 最小/最大值、空字符串、NULL、超大文本等）；
- 基类默认随机模式；子类可在 `createTestTableSpec()` 中为特定列指定固定值，构造"最小/最大/临界"数据场景。

#### 2.3.6 随机性与可复现性

- 默认无种子：每次运行取值不同，扩大数据覆盖；
- `generateRows(spec, count, seed)` 支持显式种子：带种子时由 `RandomDataFactory` 构造固定 `Random` 并注入各生成器（取代 `ThreadLocalRandom`），同一种子复现同一数据序列，配合 `RecordAssert` 快速复现失败用例。

#### 2.3.7 线程安全与性能

- `ThreadLocalRandom` 线程安全、无锁竞争，多线程并发生成（写入压测场景）无瓶颈；
- 生成器仅持有配置与边界，无共享可变状态（`SequenceGenerator` 的 `AtomicLong` 除外），可安全并发调用。

#### 2.3.8 RandomDataFactory（与 TestTableSpec 的协作）

```java
public class RandomDataFactory {
    /** 依据 TestTableSpec 为每列构建生成器；主键列自动替换为 SequenceGenerator */
    public static List<ValueGenerator<?>> createGenerators(TestTableSpec tableSpec);

    /** 生成一行：Map<字段名, 值>，按字段顺序遍历生成器 */
    public static Map<String, Object> nextRow(List<ValueGenerator<?>> generators);

    /** 批量生成 N 行（默认无种子），返回期望数据供写入后校验 */
    public static List<Map<String, Object>> generateRows(TestTableSpec tableSpec, int count);

    /** 带种子批量生成（可复现） */
    public static List<Map<String, Object>> generateRows(TestTableSpec tableSpec, int count, long seed);
}
```

#### 2.3.9 与其他模块的关系

- 消费方：`ConnectorIT` C 组用例（writeRecord/batchRead/queryByFilter）调用 `generateRows` 获得期望数据 → codec 转换 → 写入 → 读回比对；
- 供给方：`TestFieldSpec.testDataType` 决定生成器类型，`length/scale/precision` 可进一步约束生成值（如 varchar 长度上限、decimal 精度）；
- 与 `TapTypeResolver` 解耦：生成器面向"通用类型语义"，不感知 Connector 方言。

## 3. 总体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                      被测环境（真实数据库/服务）                       │
│               MySQL / Oracle / MongoDB / Kafka / ...                │
└─────────────────────────────────────────────────────────────────────┘
                                   ▲ 能力调用（ConnectorFunctions）
                                   │
┌─────────────────────────────────────────────────────────────────────┐
│  tapdata-it（test scope 依赖，通用集成测试框架）                      │
│                                                                     │
│  io.tapdata.it.ConnectorIT        —— 抽象基类：生命周期 + 全部通用用例  │
│  io.tapdata.it.ConnectorTestContext —— 测试上下文（builder）          │
│  io.tapdata.it.schema.*           —— 测试表/字段模型、通用类型定义     │
│  io.tapdata.it.generator.*        —— 随机数据生成器（ValueGenerator 体系）│
│  io.tapdata.it.mapping.*          —— 数据类型映射规则（可定制）        │
│  io.tapdata.it.assert.*           —— 数据一致性断言工具               │
│  io.tapdata.it.support.*          —— KVMap/日志/StateMap 等支撑       │
└─────────────────────────────────────────────────────────────────────┘
                                   ▲ 继承 + createContext()
                                   │
┌─────────────────────────────────────────────────────────────────────┐
│  connector 模块（tapdata-connectors / -enterprise）                  │
│  XxxConnectorIT extends ConnectorIT                                 │
│    —— 提供连接配置、初始化 Connector、注册能力、定制映射               │
└─────────────────────────────────────────────────────────────────────┘
```

依赖方向：`connector(test scope) → tapdata-it → tapdata-pdk-api / tapdata-api / tapdata-common`，严格单向，`tapdata-it` 不依赖任何具体 Connector。

## 4. 模块与包结构

`tapdata-it` 建议包结构与职责：

```
tapdata/tapdata-it/
├── pom.xml
├── docs/connector-it.md                     ← 本文档
└── src/main/java/io/tapdata/it/
    ├── ConnectorIT.java                     # 抽象基类（通用用例全集）
    ├── ConnectorTestContext.java            # 测试上下文（builder 模式）
    ├── schema/
    │   ├── TestDataType.java                # 通用数据类型枚举（见 5.3）
    │   ├── TestFieldSpec.java               # 字段规格（名称/类型/长度/精度/主键）
    │   └── TestTableSpec.java               # 测试表规格（表名生成/字段列表）
    ├── generator/
    │   ├── ValueGenerator.java              # 接口：T next(); String getColumnName()
    │   ├── BaseGenerator.java               # 抽象基类：随机边界/固定值模式
    │   ├── IntGenerator / LongGenerator / StringGenerator
    │   ├── TextGenerator / DecimalGenerator / FloatGenerator / DoubleGenerator
    │   ├── BoolGenerator / DateGenerator / DateTimeGenerator / TimestampGenerator
    │   └── RandomDataFactory.java           # 依据 TestFieldSpec 批量构建生成器/数据
    ├── mapping/
    │   └── TapTypeResolver.java           # 方言 dataType → TapType 解析（spec.json dataTypes，与引擎同源）
    ├── assert/
    │   ├── RecordAssert.java                # 记录级断言（容忍类型等价比较）
    │   └── TableAssert.java                 # 表结构断言（字段名/类型/顺序）
    └── support/
        ├── TestLog.java                     # 测试日志（TapLog 包装）
        └── TestStateMap.java                # KVMap 内存实现（供 context.setStateMap）
```

## 5. 核心类设计

### 5.1 ConnectorTestContext —— 测试上下文

承载被测 Connector 全部运行时要素，采用 builder 模式（与用户示例一致）。字段对齐 PDK 契约：

```java
package io.tapdata.it;

public class ConnectorTestContext {
    /** 被测 Connector 实例（已初始化：init + onStart 由基类负责） */
    private final TapConnector connector;
    /** Connector 任务上下文（TapConnectorContext，含 connectionConfig/nodeConfig/tableMap/stateMap） */
    private final TapConnectorContext nodeContext;
    /** 连接上下文（TapConnectionContext，discoverSchema/connectionTest 使用） */
    private final TapConnectionContext connectionContext;
    /** 能力注册表（registerCapabilities 产物，能力检测与调用入口） */
    private final ConnectorFunctions connectorFunctions;
    /** 编解码注册表（写数据时 TapValue → 原生值转换） */
    private final TapCodecsRegistry codecRegistry;
    /** 连接配置（DataMap，供连接测试/上下文构建） */
    private final DataMap config;
    /** 日志 */
    private final Log log;

    private ConnectorTestContext(Builder builder) { /* 全字段赋值 */ }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        public Builder connector(TapConnector connector) { ... }
        public Builder nodeContext(TapConnectorContext nodeContext) { ... }
        public Builder connectionContext(TapConnectionContext connectionContext) { ... }
        public Builder connectorFunctions(ConnectorFunctions connectorFunctions) { ... }
        public Builder codecRegistry(TapCodecsRegistry codecRegistry) { ... }
        public Builder config(DataMap config) { ... }
        public Builder log(Log log) { ... }
        public ConnectorTestContext build() {
            // 校验必需字段：connector / connectorFunctions / codecRegistry 非空
            // 若 connectionContext 为空则用 nodeContext 兜底（connector 需要 TapConnectorContext 时强转）
        }
    }
    // getters...
}
```

设计要点：

- `connectorFunctions` 与 `codecRegistry` 必须由 Connector 侧在 `createContext()` 中通过 `connector.registerCapabilities(functions, codecRegistry)` 产出，这是能力检测的数据源；
- `nodeContext`（`TapConnectorContext`）用于所有 connector 级函数调用；`connectionContext` 用于连接级函数（`discoverSchema`、`getTableNames`、`connectionTest`）；
- 基类会在 `@BeforeEach` 中补齐 `nodeContext` 的 `stateMap`（内存 KVMap）、`connectorCapabilities`（默认 `ConnectorCapabilities.create()` + 常用 DML 策略替代值）、`tableMap`（空实现），避免 Connector 内部 NPE；
- 若 Connector 侧只提供 `nodeContext` 而未提供 `connectionContext`，builder 自动以 `nodeContext` 兜底。

### 5.2 ConnectorIT —— 抽象基类

```java
package io.tapdata.it;

/**
 * 连接器通用集成测试基类。
 * 子类继承并实现 {@link #createContext()}，自动运行本类中全部通用集成测试用例。
 * 测试方法通过 {@link ConnectorFunctions} 检测能力，不支持的能力以 assumeTrue 跳过。
 */
public abstract class ConnectorIT {

    // ===================== 子类扩展点（abstract / protected） =====================

    /** 子类提供：初始化好的 Connector、NodeContext、能力注册表、连接配置 */
    protected abstract ConnectorTestContext createContext() throws Throwable;

    /** 子类可覆写：测试表规格（字段集合/主键/默认随机行数） */
    protected TestTableSpec createTestTableSpec() {
        return TestTableSpec.defaultAllTypesSpec();   // 覆盖 int/bigint/varchar/text/decimal/float/double/boolean/date/datetime/timestamp
    }

    /** 子类可覆写：方言数据类型解析器（默认从 @TapConnectorClass 注解加载 spec.json dataTypes） */
    protected TapTypeResolver createTypeResolver() { return TapTypeResolver.from(context.getConnector().getClass()); }

    /** 子类可覆写：单表随机行数 */
    protected int defaultRecordCount() { return 100; }

    /** 子类可覆写：测试表名前缀（默认 _tap_it_） */
    protected String tablePrefix() { return "_tap_it_"; }

    // ===================== 生命周期 =====================

    @BeforeEach
    void setUp() throws Throwable {
        context = createContext();
        // 补齐 TapConnectorContext：stateMap / connectorCapabilities / tableMap / flushOffsetCallback
        prepareContext(context);
        // 生命周期：init → onStart（Connector 建立真实连接）
        context.getConnector().init(context.connectionContextOrNode());
        if (context.connectionContextOrNode() instanceof TapConnectorContext) {
            context.getConnector().onStart(context.connectionContextOrNode());
        }
    }

    @AfterEach
    void tearDown() throws Throwable {
        dropResidualTables();                       // 兜底清理本次用例残留表
        if (context != null) {
            // releaseExternal（若注册）→ stop
            if (context.getConnectorFunctions().getReleaseExternalFunction() != null) {
                context.getConnectorFunctions().getReleaseExternalFunction().release(context.getNodeContext());
            }
            context.getConnector().stop(context.connectionContextOrNode());
        }
    }

    // ===================== 能力检测工具 =====================

    protected <F> F require(Supplier<F> getter, String capability) {
        F fn = getter.get();
        assumeTrue(fn != null, () -> "Connector does not support " + capability + ", skip.");
        return fn;
    }

    // ===================== 通用测试用例（详见第 6 章） =====================
    // 建表/删表/发现表结构/表名、写数据/行数/批读/过滤读、DDL 变更、
    // 索引/约束、事务、流读、命令、时间戳、分区表等
}
```

基类生命周期与真实引擎（`TaskService` 驱动的 PDK 节点）保持一致：`init → onStart → 能力调用 → stop`，确保被测路径与生产一致。

### 5.3 测试表/字段模型

`TestDataType` 枚举（通用类型全集，与 PDK `TapType` 一一对应）：

| TestDataType | 对应 TapType | 默认 dataType（通用） | 生成器 |
|---|---|---|---|
| INT | TapNumber(整数, 32位) | int | IntGenerator |
| BIGINT | TapNumber(整数, 64位) | bigint | LongGenerator |
| VARCHAR | TapString | varchar(255) | StringGenerator |
| TEXT | TapString(bytes 大) | text | TextGenerator |
| DECIMAL | TapNumber(fixed) | decimal(18,4) | DecimalGenerator |
| FLOAT | TapNumber(浮点, 32位) | float | FloatGenerator |
| DOUBLE | TapNumber(浮点, 64位) | double | DoubleGenerator |
| BOOLEAN | TapBoolean | boolean | BoolGenerator |
| DATE | TapDate | date | DateGenerator |
| DATETIME | TapDateTime | datetime | DateTimeGenerator |
| TIMESTAMP | TapDateTime(fraction) | timestamp | TimestampGenerator |

`TestFieldSpec`：`name`、`dataType`（被测 Connector 方言类型，经 TapTypeResolver 按 spec 断言）、`testDataType`（生成器选择依据）、`length/scale/precision`、`primaryKey`、`autoInc`、`nullable`。

`TestTableSpec`：

```java
public class TestTableSpec {
    private final String tableName;                 // 随机生成，如 _tap_it_2f3a9c1e7b
    private final List<TestFieldSpec> fields;       // 有序字段列表
    private final int recordCount;                  // 默认随机数据行数

    public static TestTableSpec defaultAllTypesSpec() {
        return builder()
            .tableName(randomTableName("_tap_it_"))
            .addField(TestFieldSpec.builder().name("id").dataType("bigint").testDataType(BIGINT).primaryKey(true).build())
            .addField(TestFieldSpec.builder().name("c_int").dataType("int").testDataType(INT).build())
            .addField(TestFieldSpec.builder().name("c_bigint").dataType("bigint").testDataType(BIGINT).build())
            .addField(TestFieldSpec.builder().name("c_varchar").dataType("varchar(255)").testDataType(VARCHAR).build())
            .addField(TestFieldSpec.builder().name("c_text").dataType("text").testDataType(TEXT).build())
            .addField(TestFieldSpec.builder().name("c_decimal").dataType("decimal(18,4)").testDataType(DECIMAL).build())
            .addField(TestFieldSpec.builder().name("c_float").dataType("float").testDataType(FLOAT).build())
            .addField(TestFieldSpec.builder().name("c_double").dataType("double").testDataType(DOUBLE).build())
            .addField(TestFieldSpec.builder().name("c_boolean").dataType("boolean").testDataType(BOOLEAN).build())
            .addField(TestFieldSpec.builder().name("c_date").dataType("date").testDataType(DATE).build())
            .addField(TestFieldSpec.builder().name("c_datetime").dataType("datetime").testDataType(DATETIME).build())
            .addField(TestFieldSpec.builder().name("c_timestamp").dataType("timestamp").testDataType(TIMESTAMP).build())
            .build();
    }
}
```

要点：

- **表名随机**：`_tap_it_` + 时间戳（8 位 Base36）+ 8 位随机串，避免依赖/污染被测环境既有数据；每次用例独立建表，`@AfterEach` 统一删除；
- **字段覆盖通用类型**：int/bigint/varchar/text/decimal/float/double/boolean/date/datetime/timestamp 等；NoSQL Connector（如 MongoDB）无需关注建表 DDL，其 `createTableV2` 通常为幂等空实现，字段模型仍然通用；
- 子类可覆写 `createTestTableSpec()` 增加专属类型（如 `c_year`、`c_binary`、`c_map`、`c_array`）。

### 5.4 随机数据生成器（实现要点）

生成器体系与值域策略的详细设计见 2.3。接口 `ValueGenerator<T>` 与抽象基类 `BaseGenerator<T>`（随机/固定值双模式、默认边界）的完整定义见 2.3.2，本节仅给出各类型生成器的实现策略与工厂协作：

各类型生成器实现策略（全部使用 `ThreadLocalRandom` 保证高并发测试下的性能与线程安全，默认值域见 2.3.3）：

| 生成器 | 生成策略 |
|---|---|
| `IntGenerator` | `ThreadLocalRandom.current().nextInt(min, max+1)` |
| `LongGenerator` | `nextLong(min, max+1)` |
| `StringGenerator` | 预置字母数字字符表按下标随机取值，长度 16~32 |
| `TextGenerator` | 长字符串（含空格与标点，长度 256~1024），覆盖 text 类型 |
| `DecimalGenerator` | `BigDecimal.valueOf(nextDouble(min, max)).setScale(4, HALF_UP)`，保证小数位 |
| `FloatGenerator` / `DoubleGenerator` | `nextDouble(min, max)` 转 float/double |
| `BoolGenerator` | `nextBoolean()` |
| `DateGenerator` | `LocalDate.ofEpochDay(nextLong(起始日, 结束日))`，范围 2020-01-01 ~ 当前日期 |
| `DateTimeGenerator` | `LocalDateTime.ofInstant(Instant.ofEpochMilli(nextLong(...)), ZoneOffset.UTC)` |
| `TimestampGenerator` | 同 DateTime，附纳秒/毫秒小数部分（fraction=3/6），覆盖精度差异 |
| `SequenceGenerator` | `AtomicLong` 递增（主键专用，保证 PK 唯一） |

`RandomDataFactory`（按 `TestTableSpec` 生成数据行）：

```java
public class RandomDataFactory {
    /** 为表规格构建每列生成器（主键列自动使用 SequenceGenerator） */
    public static List<ValueGenerator<?>> createGenerators(TestTableSpec tableSpec);

    /** 生成一行数据：Map<字段名, 值>，主键自动递增 */
    public static Map<String, Object> nextRow(List<ValueGenerator<?>> generators);

    /** 批量生成：List<Map<String, Object>>，并同时返回"期望数据"（供写入后校验） */
    public static List<Map<String, Object>> generateRows(TestTableSpec tableSpec, int count);
}
```

要点：

- 生成的数据行会被基类**保留为期望值**（`expectedRows`），写入被测库后经 `batchRead`/`queryByFilter` 读回，逐行逐列比对，实现数据一致性验证；
- 日期/时间范围固定（2020-01-01 ~ 当前时间），避免数据库时间边界问题（如 MySQL timestamp 上限 2038 年）；
- DECIMAL 固定 scale=4，规避浮点/定点精度断言陷阱；FLOAT/DOUBLE 断言时允许相对误差（见 5.6）。

### 5.5 数据类型解析（TapTypeResolver）

`discoverSchema` 返回的 `TapField.dataType` 是 **Connector 方言**（MySQL 返回 `int`、`varchar(255)`；MongoDB 返回 `Int32`、`Int64`；DB2 i 返回 `INTEGER`、`FLOAT(4)`）。与引擎 wrap 链路一致——引擎根据 connector spec.json 的 `dataTypes` 声明将方言值包装为 TapXxxx 类型，因此集成测试不再维护方言映射表，直接以 spec 声明为准：

```java
public class TapTypeResolver {
    /** 从 Connector 类 @TapConnectorClass 注解读取 spec 文件名（如 spec_db2.json）并加载 dataTypes */
    public static TapTypeResolver from(Class<?> connectorClass);

    /** 方言 dataType → TapType（与引擎 TableFieldTypesGenerator 同款解析：表达式匹配 + 参数提取） */
    public TapType resolve(String dataType);

    /** 方言 dataType 是否被 spec dataTypes 声明（表达式匹配 + 大小写不敏感） */
    public boolean isDeclared(String dataType);
}
```

`ConnectorIT` 默认从 `context.getConnector().getClass()` 读取 `@TapConnectorClass` 注解自动加载（如 `Db2Connector` 上的 `@TapConnectorClass("spec_db2.json")`），子类无需覆写。匹配规则与引擎一致：表达式匹配 + 大小写不敏感（如 MySQL `int unsigned`、DB2 i `FLOAT(4)`、MongoDB `Int32` 均可匹配 spec 中的 `int`、`FLOAT($precision)`、`INT32`）。

表结构断言流程（`assertSchema`）：

1. 调用 `discoverSchema(context, [tableName], 1, consumer)` 取回该表的 `TapTable`；
2. 按 `TestTableSpec` 字段顺序逐一断言：字段存在、`tapType` 类型族一致（`TapNumber`/`TapString`/`TapBoolean`/`TapDate`/`TapDateTime`/`TapTime`；BOOLEAN 兼容 `TapNumber(bit<=16)`，如 DB2 i SMALLINT、MySQL tinyint 承载）、`dataType` 被 spec dataTypes 声明；
3. 主键字段断言 `primaryKey=true`（若 Connector 支持主键发现）。

Connector spec 未声明某方言类型（如缺失 REAL 声明）时断言失败，提示补充 spec dataTypes——保证 spec 声明与实际 schema 能力一致。Connector 类缺失 `@TapConnectorClass` 注解时可覆写 `createTypeResolver()` 定制。

### 5.6 断言工具

- `TableAssert.assertFields(...)`：字段名集合/顺序/类型族断言（见 5.5）；
- `RecordAssert.assertEquals(TestDataType, expected, actual)`：按类型分派比较——
  - 数值族（int/bigint/decimal/float/double）：`BigDecimal` 归一化比较，float/double 允许相对误差 `1e-6`；
  - 布尔族：兼容 `true/1/1.0` 与 `false/0/0.0`（部分库 boolean 以 NUMBER(1)/bit(1) 存储）；
  - 日期族：`LocalDate/LocalDateTime/ZonedDateTime/Timestamp/java.util.Date` 统一转 `LocalDateTime`（UTC）比较，容忍毫秒精度差异（fraction 不一致时截断到秒）；
  - 字符串族：去尾部空白后比较（char/text 存储差异）；
  - 断言失败信息包含列名、期望值、实际值、类型，便于定位。

## 6. 通用测试用例设计

### 6.1 能力检测与跳过机制

所有测试方法遵循同一模式：

```java
@Test
void should_write_and_batch_read() throws Throwable {
    WriteRecordFunction writeRecord = require(context.getConnectorFunctions()::getWriteRecordFunction, "writeRecord");
    BatchCountFunction batchCount = require(context.getConnectorFunctions()::getBatchCountFunction, "batchCount");
    BatchReadFunction batchRead = require(context.getConnectorFunctions()::getBatchReadFunction, "batchRead");
    // ... 用例主体
}
```

`require` 内部使用 `assumeTrue(fn != null, ...)`：能力未注册时该测试**跳过**（not run / skipped），不影响其他用例与整体报告。

**能力分组策略**：

| 分组 | 能力 | 策略 |
|---|---|---|
| 核心能力（绝大多数 Connector 必须） | createTableV2、dropTable、writeRecord、batchCount、batchRead、discoverSchema、getTableNames | 断言必须支持（不满足则直接 fail），并全部覆盖测试 |
| 常见能力 | clearTable、queryByFilter、queryByAdvanceFilter、getTableInfo、executeCommand、transaction* | assumeTrue 跳过 |
| 高级能力 | streamRead、timestampToStreamOffset、createIndex/deleteIndex/queryIndexes、createConstraint/queryConstraints/dropConstraint、newField/dropField/alterField*、partition*、queryHashByAdvanceFilter、runRawCommand、countRawCommand、exportEventSql、afterInitialSync、getCurrentTimestamp、control、processControl、flushOffset、getStreamOffset、streamReadOneByOne、streamReadMultiConnection、alterDatabaseTimeZone、alterTableCharset、alterTableTTL、errorHandle、checkTableName、getCharsets、connectionCheck、executeCommandV2、commandCallback、connectorWebsite、tableWebsite | assumeTrue 跳过 |

> 说明：`discoverSchema`/`connectionTest`/`tableCount` 是 `TapConnectorNode` 接口方法而非 ConnectorFunctions 能力，直接调用接口即可；`getTableNames` 属于 `ConnectionFunctions`（父类），通过 `connectorFunctions.getGetTableNamesFunction()` 检测。

### 6.2 用例清单（ConnectorFunctions 全能力覆盖）

按职责分组，每个用例一个 `@Test` 方法，命名 `should_*`：

#### A. 连接与元数据
| # | 用例 | 涉及能力 | 验证点 |
|---|---|---|---|
| A1 | should_test_connection | `connectionTest`（接口） | 返回 `ConnectionOptions` 且无 fail 项 |
| A2 | should_discover_schema_of_created_table | `discoverSchema`（接口） | 字段名/类型族/dataType 映射/主键符合 `TestTableSpec` |
| A3 | should_find_table_after_create | `getTableNames` | 建表后表名出现在表清单中 |
| A4 | should_table_not_found_after_drop | `getTableNames` + `dropTable` | 删表后表名消失 |
| A5 | should_get_table_info | `getTableInfo` | 返回 `TableInfo` 且行数/存储量非负 |
| A6 | should_check_table_name | `checkTableName` | 合法表名校验通过 |
| A7 | should_get_charsets | `getCharsets` | 返回非空字符集列表 |
| A8 | should_connection_check | `connectionCheck` | 返回校验结果 |

#### B. DDL（表级）
| # | 用例 | 涉及能力 | 验证点 |
|---|---|---|---|
| B1 | should_create_table_v2 | `createTableV2` | 返回 `CreateTableOptions`；`tableExists=false`（新建）；A3 复核存在 |
| B2 | should_create_table_v2_when_exists | `createTableV2` | 二次调用 `tableExists=true`，不抛错（幂等） |
| B3 | should_clear_table | `clearTable` | 写入后清空，`batchCount == 0` |
| B4 | should_drop_table | `dropTable` | A4 复核不存在；再次 drop 不抛错（幂等） |
| B5 | should_alter_table_charset | `alterTableCharset` | 事件执行成功 |
| B6 | should_alter_table_ttl | `alterTableTTL` | 事件执行成功（MongoDB 等） |
| B7 | should_alter_database_timezone | `alterDatabaseTimeZone` | 执行成功 |

#### C. 数据写入与读取（核心）
| # | 用例 | 涉及能力 | 验证点 |
|---|---|---|---|
| C1 | should_write_insert_records | `writeRecord` | 写入 N 条 insert（含全部类型列），回调 `WriteListResult.insertedCount == N` |
| C2 | should_batch_count_match | `batchCount` | 写入后 `count == N` |
| C3 | should_batch_read_data_consistent | `batchRead` | 读回 N 条，逐行逐列与期望值比对（`RecordAssert`） |
| C4 | should_write_update_records | `writeRecord`（update 事件） | 按主键更新 M 条，batchRead 复核值已更新 |
| C5 | should_write_delete_records | `writeRecord`（delete 事件） | 按主键删除 M 条，`batchCount == N - M` |
| C6 | should_query_by_filter | `queryByFilter` | 按主键等值查询命中 1 条且数据一致 |
| C7 | should_query_by_advance_filter | `queryByAdvanceFilter` | 范围/条件查询（如 `c_int > X`、`limit`），结果与期望过滤结果一致 |
| C8 | should_after_initial_sync | `afterInitialSync` | 执行成功（自增修正等） |

#### D. 索引与约束
| # | 用例 | 涉及能力 | 验证点 |
|---|---|---|---|
| D1 | should_create_and_query_index | `createIndex` + `queryIndexes` | 创建普通/唯一索引后 `queryIndexes` 可见 |
| D2 | should_delete_index | `deleteIndex` | 删除后 `queryIndexes` 不再包含 |
| D3 | should_create_and_query_constraint | `createConstraint` + `queryConstraints` | 创建外键/唯一约束后查询可见 |
| D4 | should_drop_constraint | `dropConstraint` | 删除后不可见 |

#### E. 字段级 DDL
| # | 用例 | 涉及能力 | 验证点 |
|---|---|---|---|
| E1 | should_new_field | `newField` | 新增列后 discoverSchema 可见新字段（nullable/默认值） |
| E2 | should_drop_field | `dropField` | 删除列后 discoverSchema 不含该字段 |
| E3 | should_alter_field_name | `alterFieldName` | 改名后 discoverSchema 新名可见 |
| E4 | should_alter_field_attributes | `alterFieldAttributes` | 修改长度/精度后 discoverSchema 反映 |

#### F. 流式读取（增量）
| # | 用例 | 涉及能力 | 验证点 |
|---|---|---|---|
| F1 | should_timestamp_to_stream_offset | `timestampToStreamOffset` | 返回非空 offset |
| F2 | should_stream_read_incremental | `streamRead` | 用 `timestampToStreamOffset(now)` 定位后写入 M 条，`streamRead` 在超时窗口内收到全部 M 条且数据一致 |
| F3 | should_stream_read_one_by_one | `streamReadOneByOne` | 逐条收到增量事件 |
| F4 | should_stream_read_multi_connection | `streamReadMultiConnection` | 多连接配置下可读到数据（企业能力） |

#### G. 事务
| # | 用例 | 涉及能力 | 验证点 |
|---|---|---|---|
| G1 | should_transaction_commit | `transactionBegin/Commit` + `writeRecord` | 事务内写入后 commit，`batchCount` 反映 |
| G2 | should_transaction_rollback | `transactionBegin/Rollback` + `writeRecord` | 事务内写入后 rollback，`batchCount` 不增加 |

#### H. 命令与原始操作
| # | 用例 | 涉及能力 | 验证点 |
|---|---|---|---|
| H1 | should_execute_command | `executeCommand` | 执行受支持命令（如 `ping`）返回成功 |
| H2 | should_execute_command_v2 | `executeCommandV2` | 同上 |
| H3 | should_run_raw_command | `runRawCommand` | 执行查询类原始命令，返回结果集 |
| H4 | should_count_raw_command | `countRawCommand` | 返回行数与 batchCount 一致 |
| H5 | should_export_event_sql | `exportEventSql` | 对 insert 事件导出 SQL 非空 |

#### I. 其他能力
| # | 用例 | 涉及能力 | 验证点 |
|---|---|---|---|
| I1 | should_get_current_timestamp | `getCurrentTimestamp` | 返回时间与当前时间偏差在阈值内 |
| I2 | should_query_hash_by_advance_filter | `queryHashByAdvanceFilter` | 返回 `TapHashResult` 且与期望 hash 一致（或非空） |
| I3 | should_control / process_control | `control` / `processControl` | 执行成功 |
| I4 | should_flush_offset / get_stream_offset | `flushOffset` / `getStreamOffset` | 回调被触发 / 返回非空 |
| I5 | should_error_handle | `errorHandle` | 构造异常场景返回 `RetryOptions` |
| I6 | should_partition_table_ops | `createPartitionTable` / `createPartitionSubTable` / `dropPartitionTable` / `queryPartitionTablesByParentName` | 分区表创建/查询/删除闭环 |
| I7 | should_count_by_partition_filter | `countByPartitionFilter` | 分区条件下计数正确 |
| I8 | should_get_read_partitions / query_field_min_max | `getReadPartitions` / `queryFieldMinMaxValue` | 分区切片可生成、min/max 正确 |
| I9 | should_connector_website / table_website | `connectorWebsite` / `tableWebsite` | 返回站点信息非空 |
| I10 | should_command_callback | `commandCallback` | 回调收到命令 |

> 每个用例均遵守"独立建表 → 操作 → 校验 → 清理"的隔离原则；核心用例（C1~C3）在 `@BeforeEach` 建表、`@AfterEach` 删表，其余用例视需要复用 `createTempTable()` 工具。

### 6.3 核心用例流程详解（C1~C3 串联）

```
@Test  should_write_insert_records
  ┌─ createTableV2(TapCreateTableEvent(table = buildTapTable(spec)))   // B1 前置建表
  ├─ 生成 N 行随机数据 expectedRows（RandomDataFactory，主键 1..N）
  ├─ 每行转 TapInsertRecordEvent：TapCodecsFilterManager.create(codecRegistry)
  │        .transformFromTapValueMap(row, tapTable.getNameFieldMap())
  │     → after(row) → table(tableId)
  ├─ writeRecord(context, events, tapTable, result -> insertedCount 累加)
  └─ assert insertedCount == N

@Test  should_batch_count_match
  ├─ 前置：建表 + 写入 N 行（复用 beforeEach 工具方法 prepareData())
  └─ batchCount(context, tapTable) == N

@Test  should_batch_read_data_consistent
  ├─ 前置：建表 + 写入 N 行
  ├─ batchRead(context, tapTable, null, 100, (events, offset) -> 收集 TapInsertRecordEvent)
  ├─ 收集完成后按主键索引实际行
  └─ 逐行逐列 RecordAssert.assertEquals(testDataType, expected, actual)
```

关键实现细节：

- **TapTable 构建**：`TapSimplify.table(tableName)` + 逐字段 `TapSimplify.field(name, mappedDataType)` 设置 `tapType`（`tapNumber()/tapString()/tapBoolean()/tapDate()/tapDateTime()` 等）、`primaryKey`/`primaryKeyPos`；不要依赖 `TableFieldTypesGenerator` 自动填充（它需要 spec dataTypes 映射，通用用例直接手工设置更可控）；
- **值转换**：写入前用 `TapCodecsFilterManager.create(context.getCodecRegistry()).transformFromTapValueMap(row, tapTable.getNameFieldMap())` 将普通值转为 `TapValue`，与引擎 `PdkTargetNode` 行为一致（引擎写入路径同样经过 codec 转换），保证 Connector 的 `registerFromTapValue` 定制逻辑（如 MySQL 日期格式）被真实触发；
- **读回转换**：`batchRead` 返回的 `TapInsertRecordEvent.getAfter()` 是原生 Map，直接与期望值比较；Connector 的 `registerToTapValue` 只影响 source 侧 codec，批读结果一般已是原生类型；
- **DML 策略**：写入前在 `connectorContext.getConnectorCapabilities()` 注册 `DML_INSERT_POLICY=JUST_INSERT`、`DML_UPDATE_POLICY=IGNORE_ON_NON_EXISTS`、`DML_DELETE_POLICY=IGNORE_ON_NON_EXISTS` 替代值，规避部分 Connector 默认"存在即更新"策略对 delete 用例的干扰。

## 7. Connector 接入指南

### 7.1 Maven 依赖配置（connector 模块 pom.xml）

```xml
<dependency>
    <groupId>io.tapdata</groupId>
    <artifactId>tapdata-it</artifactId>
    <version>${project.version}</version>
    <scope>test</scope>
</dependency>
<!-- 测试运行需真实驱动与 JUnit5（一般已具备） -->
```

同时需在 `tapdata/pom.xml` 的 `<modules>` 中加入 `tapdata-it`（或保持现有独立构建方式），并确认 `tapdata-it` 的 pom 声明 `tapdata-pdk-api`、`tapdata-api`（即 `tapdata-common-lib` 产物）为依赖。

### 7.2 实现 createContext（MySQL 示例）

```java
package io.tapdata.connector.mysql;

import io.tapdata.connector.mysql.MysqlConnector;
import io.tapdata.entity.codec.TapCodecsRegistry;
import io.tapdata.entity.logger.TapLog;
import io.tapdata.entity.utils.DataMap;
import io.tapdata.it.ConnectorIT;
import io.tapdata.it.ConnectorTestContext;
import io.tapdata.pdk.apis.context.TapConnectorContext;
import io.tapdata.pdk.apis.functions.ConnectorFunctions;
import org.junit.jupiter.api.TestInstance;

/**
 * MySQL Connector 集成测试。
 * 连接配置读取 src/test/resources/config/connection.json（host/port/database/user/password）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySQLConnectorIT extends ConnectorIT {

    @Override
    protected ConnectorTestContext createContext() throws Throwable {
        // 1. 创建连接器
        MysqlConnector connector = new MysqlConnector();

        // 2. 连接配置（推荐从 connection.json 读取，支持环境变量覆盖）
        DataMap config = readConnectionConfig("config/connection.json");

        // 3. 构建 NodeContext：specification + connectionConfig + nodeConfig + log
        TapConnectorContext nodeContext = new TapConnectorContext(
                null, config, DataMap.create().kv("enableTransaction", true), new TapLog());
        nodeContext.setStateMap(new TestStateMap());      // 内存 KVMap，部分 Connector onStart 需要

        // 4. 注册能力
        ConnectorFunctions functions = new ConnectorFunctions();
        TapCodecsRegistry codecRegistry = TapCodecsRegistry.create();
        connector.registerCapabilities(functions, codecRegistry);

        // 5. 组装上下文
        return ConnectorTestContext.builder()
                .connector(connector)
                .nodeContext(nodeContext)
                .connectorFunctions(functions)
                .codecRegistry(codecRegistry)
                .config(config)
                .log(new TapLog())
                .build();
    }
}
```

Connector 侧只需提供连接等必需配置，测试表、测试数据、用例全部由通用基类提供。

### 7.3 配置文件约定

- 推荐 `src/test/resources/config/connection.json`（参考 mysql-connector 既有测试目录结构），键名对齐该 Connector 的 `xxx-spec.json` 连接表单字段；
- 敏感信息（密码）支持系统属性/环境变量覆盖：`-Dconnector.it.password=xxx` 或 `CONNECTOR_IT_PASSWORD=xxx`；
- 基类提供 `readConnectionConfig(path)` 与 `sysPropOrEnv(key, defaultValue)` 工具。

## 8. 扩展性与定制机制

面向不同数据源/类型体系的 Connector，提供以下扩展点（按优先级）：

| 扩展点 | 默认行为 | 定制场景 |
|---|---|---|
| `createTestTableSpec()` | 全通用类型（5.3） | 增加专属类型列（binary/year/map/array/geojson…）；减少不支持类型 |
| `createTypeResolver()` | 从 Connector 类 `@TapConnectorClass` 注解自动加载 spec.json dataTypes | Connector 类缺失注解或 spec 未声明时覆写定制 |
| `defaultRecordCount()` | 100 | 大数据量压力验证；小数据量快速验证 |
| `tablePrefix()` | `_tap_it_` | 避免与业务前缀冲突 |
| `assertSchema(...)` | 通用字段断言 | 无 schema 概念（对象存储、消息队列）或类型无法映射 |
| `ValueGenerator` 体系 | 默认边界 | 特殊取值范围（如负数、极端精度） |
| `prepareData(...)` / `beforeWrite(...)` | 随机数据 + codec 转换 | 需要先构造依赖数据/序列、约束 |
| `skipCapability(...)` | 不跳过 | 主动屏蔽已知有缺陷的能力（配合 `@Disabled` 说明原因） |
| `isSchemaRequired` / `supportsTableDDL` 开关 | true | 文件/消息类 Connector 无建表概念时，基类自动退化（不执行 B 组、C 组改从 getTableNames 已有表准备） |

**NoSQL / 无表 DDL Connector 适配策略**：基类通过"能力检测 + 开关"双通道适配——若 `getCreateTableV2Function() == null` 且 `getDropTableFunction() == null`，则 B 组用例全部跳过，C 组用例改为：从 `getTableNames` 选取或提示子类在 `createContext` 中预先建好表并覆写 `resolveTestTable()` 返回实际表名。

## 9. 测试执行方式

### 9.1 Maven Surefire（推荐，单模块快速执行）

```bash
# 先安装 tapdata-it 及公共库到本地仓库
mvn -pl tapdata/tapdata-it -am install -DskipTests
# 执行 MySQL Connector 集成测试（连接配置经 -D 传入）
mvn -pl tapdata-connectors/connectors/mysql-connector \
    -Dtest=MySQLConnectorIT \
    -Dconnector.it.host=127.0.0.1 -Dconnector.it.port=3306 \
    -Dconnector.it.database=test -Dconnector.it.user=root -Dconnector.it.password=xxx \
    test
```

> 注意：Surefire 默认不执行 `*IT` 命名类，需在 connector 模块的 surefire 配置中添加 `<includes><include>**/*IT.java</include></includes>`，或类名改为 `MySQLConnectorIT` 并显式指定 `-Dtest=MySQLConnectorIT`（该参数可覆盖默认 include 规则）。

### 9.2 Maven Failsafe（集成测试阶段）

```bash
mvn -pl tapdata-connectors/connectors/mysql-connector integration-test failsafe:verify
```

### 9.3 测试报告

- 跳过用例（能力不支持）在报告中以 `SKIPPED` 标识，便于区分"未实现"与"未通过"；
- 建议 CI 为每个 Connector 准备独立环境（docker-compose 起库），按 9.1 逐模块执行。

## 10. 风险与注意事项

1. **能力检测的完备性**：`ConnectorFunctions.getCapabilities()` 基于反射，测试中一律用 getter 判空，不依赖 capability 名称字符串；
2. **数据污染**：所有表名随机且 `@AfterEach` 兜底 drop（即使断言失败也执行），drop 用 `dropTable` 能力；若 drop 不可用（无删表权限），基类记录残留表名并在报告 warning；
3. **时间与时区**：日期断言统一走 UTC 归一化；`timestamp` 列测试时避免使用 `LocalDateTime.now()` 期望值（写入与读回存在时区偏移），期望值全部来自生成器（UTC 固定基准）；
4. **精度**：float/double 用相对误差断言；decimal 固定 scale；避免 `assertEquals(double, double)`；
5. **幂等性**：`createTableV2` 重复调用、`dropTable` 删除不存在表，均不得抛错（部分 Connector 需在用例中容忍）；
6. **资源释放**：`tearDown` 必须执行 `releaseExternal`（若注册）+ `stop`，否则连接泄漏导致后续用例超时；
7. **并行执行**：默认单线程执行（`@Execution(SAME_THREAD)`）；开启 JUnit 并行时确保 `tablePrefix + UUID` 表名唯一（已天然满足）；
8. **企业版 Connector**：`tapdata-connectors-enterprise` 同样以 test scope 依赖 `tapdata-it`，其内部驱动/加密类不可用场景需在 `createContext` 中按需解密（参考 `HotLoadConnectorTester` 的 `JarEncryptor` 处理）；
9. **与引擎的关系**：本框架直连 `ConnectorFunctions`，跳过引擎侧 DAG 编排；引擎级端到端验证（`DataSyncEventHandler` 全链路）仍由 `iengine-app` 现有测试与 `auto-test` 承担，二者互补。

## 11. 里程碑规划

| 阶段 | 内容 | 产出 |
|---|---|---|
| M1 | 框架骨架：`ConnectorTestContext` + `ConnectorIT` 生命周期 + 能力检测 + 表模型 + 生成器 + TapTypeResolver + 断言 | tapdata-it 可编译、mock-source-connector 跑通 A/B/C 组 |
| M2 | 全能力用例补齐（D~I 组） | 用例全量落地，能力检测全覆盖 |
| M3 | 首批 Connector 接入：mysql、postgres、mongodb、oracle（enterprise） | 4 个 `XxxConnectorIT` 示例 |
| M4 | CI 接入：docker-compose 环境 + failsafe 阶段 + 跳过率/通过率报告 | CI 流水线稳定运行 |
