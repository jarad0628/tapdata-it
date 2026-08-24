# tapdata-it 架构说明（基于最新实现）

> 版本：v2.0（对应旁路验证器 + 声明式能力改造后的最新代码）
> 目标模块：`tapdata/tapdata-it`
> 前置阅读：[connector-it.md](./connector-it.md)（v1.0 设计文档，本文档以最新实现为准）

## 1. 模块定位与核心设计理念

`tapdata-it` 是 TapData 连接器生态的**通用集成测试框架**：一份测试用例全集（61 个），任何 Connector 只需继承 `ConnectorIT` 并提供连接上下文，即可自动运行全部用例，覆盖 `ConnectorFunctions` 的绝大多数能力（建表/删表/读写/索引/约束/字段 DDL/流式/事务/命令/分区等）。

最新实现围绕三条**可靠性原则**构建：

| 原则 | 含义 | 落地机制 |
|---|---|---|
| **原则 1：旁路验证** | 验证事实来源必须是**对端数据源**，禁止"用 Connector 的 read 验证 Connector 的 write"；数据/结构准备也走旁路 | `ConnectorVerifier` 体系（直连 JDBC/MongoDB） |
| **原则 2：动作唯一** | 每个用例只被测一个 function，其余能力一律旁路准备 | 用例拆分 + 旁路准备工具 |
| **原则 3：声明式能力** | connector 声明必实现接口（`requiredCapabilities()`，默认 = 已注册全部实现），框架校验：不遗漏必实现接口（声明未实现→FAIL）+ 已实现接口（含未声明）必须被用例覆盖；旁路验证用例无验证器时直接失败 | `requiredCapabilities()` 扩展点 + 框架级校验用例 + `require()` 三态判定 + setUp 强校验 |

```
┌─────────────────────────────────────────────────────────────────┐
│                   被测环境（真实数据库/服务）                       │
│          MySQL / MongoDB / DB2 i / Oracle / ...                 │
└───────────────▲───────────────────────────────▲─────────────────┘
       能力调用（ConnectorFunctions）     旁路直连（JDBC / MongoClient）
        ┌───────┴────────┐                  ┌────────┴────────┐
┌───────┴─────────────────────────────────────────────────────────┐
│  tapdata-it（通用集成测试框架）                                   │
│                                                                 │
│  ConnectorIT（抽象基类：生命周期 + 61 个通用用例 + 工具方法）       │
│    ├── 声明式能力：requiredCapabilities() 必实现声明 + 覆盖校验用例  │
│    ├── verifier/  旁路验证器（ConnectorVerifier / Factory / Jdbc / Mongo）│
│    ├── schema/    测试表/字段模型（TestTableSpec / TestFieldSpec / TestDataType）│
│    ├── generator/ 随机数据生成器（ValueGenerator 体系 + RandomDataFactory）│
│    ├── mapping/   TapTypeResolver（方言类型 → TapType，与引擎同源） │
│    ├── asserts/   RecordAssert / TableAssert（类型等价比较断言）    │
│    ├── support/   内存 KVMap / 日志等运行时支撑                    │
│    └── ConnectorTestContext（测试上下文：builder + 特性开关）       │
└───────────────▲─────────────────────────────────────────────────┘
                │ 继承 + createContext()
┌───────────────┴─────────────────────────────────────────────────┐
│  connector 模块：XxxConnectorIT extends ConnectorIT              │
│   MySQLConnectorIT（最小接入）/ MongoDBConnectorIT（特性开关）      │
│   DB2iConnectorIT（方言适配 + 验证器定制）                        │
└─────────────────────────────────────────────────────────────────┘
```

依赖方向严格单向：`connector(test scope) → tapdata-it → tapdata-pdk-api / tapdata-api`。
**`tapdata-it` 不依赖任何具体 Connector、不依赖 sql-core、不依赖 mongodb-driver**（旁路验证器全程字符串类名反射），这是"一份用例全生态复用"的模块基础。

## 2. 组件详解

### 2.1 ConnectorIT —— 抽象基类（核心）

[ConnectorIT.java](../src/main/java/io/tapdata/it/ConnectorIT.java)（约 1927 行）承担四类职责：

**① 生命周期驱动（与引擎 PdkNode 一致）**

```
@BeforeEach setUp:
  createContext() → createTestTableSpec() → createTypeResolver()
  → prepareContext()（补齐 stateMap / connectorCapabilities / tableMap / flushOffsetCallback）
  → connector.init() → createVerifier() 自动装配
  → 强校验：@UnderTest(requiresVerifier=true) 但 verifier==null → fail
@AfterEach tearDown:
  dropResidualTables()（旁路优先）→ verifier.close() → releaseExternal() → stop()
```

- `prepareContext` 补齐引擎运行时要素：`stateMap`（内存 KVMap）、`connectorCapabilities`（DML 策略与引擎一致：`JUST_INSERT` / `IGNORE_ON_NON_EXISTS`）、`tableMap`（内存 KVMap）、`flushOffsetCallback`（低版本 pdk-api 反射跳过）。
- `tearDown` 无论断言成败都执行残留表清理与 `stop()`，防止连接泄漏；堆内存用量在用例边界打印，用于发现资源泄漏。

**② 16 个子类扩展点（详见第 4 章）**

**③ 能力检测工具（原则 3）**

```java
protected <F> F require(Supplier<F> getter, String capability) {
    F fn = getter.get();
    if (fn == null) {
        // 该能力属于 connector 声明的必实现集合 → 断言失败（declared but not implemented）
        if (requiredCapabilities().contains(capability)) {
            fail("... declares required capability " + capability + " but connector does not implement it");
        }
        // 未声明 → 维持 assumeTrue 跳过
    }
    assumeTrue(fn != null, () -> "Connector does not support " + capability + ", skip.");
    return fn;
}
```

三态语义：**必实现声明了未实现 → FAIL；未声明未实现 → SKIP；已实现 → 测试**。`reflectFunction()` 是 `require` 的反射版，用于 pdk-api 2.0.8 才新增的能力（alterTableTTL/processControl），低版本环境自动跳过。

框架另有三个自检方法：`requiredCapabilities()`（connector 声明必实现接口，默认 = 当前已注册的全部接口实现）、`implementedCapabilities()`（反射扫描运行时 `ConnectorFunctions` 全部 getter，非 null 即已实现，排除 `ignoredCapabilities()` 引擎钩子，静态并入 connectionTest/discoverSchema）、`coveredCapabilities()`（扫描类层级全部用例的 `@UnderTest` 集合）。校验用例 `should_all_capabilities_implemented_and_covered` 强制三集合关系 `declared ⊆ implemented ⊆ covered`。

**④ 用例全集与工具方法**

- 用例按 A~J 十组组织（见第 3 章），每个用例 `@Test + @UnderTest`。
- 工具方法分两类：
  - **被测侧**：`writeInsertEvents/writeUpdateEvents/writeDeleteEvents`（TapRecordEvent 构建 + writeRecord 调用，与引擎 PdkTargetNode 一致）、`batchReadAll`、`discoverTable`、`tableNames` 等；
  - **旁路侧（原则 1）**：`createTableIfNeeded`（一律 `verifier().createTable`，无验证器时 fail，**禁止降级到 connector**）、`bypassInsert`（旁路写入 + count 确认）、`prepareData`（旁路建表+写数，返回期望数据）、`verifyCount`、`verifyRowsByPk`、`pkValues`、`dropResidualTables`（旁路优先）。

### 2.2 @UnderTest —— 用例能力声明注解

[UnderTest.java](../src/main/java/io/tapdata/it/UnderTest.java)（`@Repeatable`，容器注解 [UnderTests.java](../src/main/java/io/tapdata/it/UnderTests.java)）：

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(UnderTests.class)
public @interface UnderTest {
    String value();                              // 本用例被测的 ConnectorFunctions 能力名
    boolean requiresVerifier() default false;    // 必须旁路验证（数据类用例为 true）
}
```

- **不承担必实现声明职责**：必实现接口清单由子类 `requiredCapabilities()` 声明（见 2.1 ③），`@UnderTest` 仅声明“本用例测哪个能力”，作为覆盖校验输入与旁路验证要求判定依据；
- `setUp` 中反射读取当前方法全部注解存入 `currentUnderTests`（数组，支持一用例多能力——事务用例同时标注 transactionBegin/transactionCommit），供 requiresVerifier 判定；
- `requiresVerifier=true` 且旁路验证器不可用 → **用例直接失败**（提示子类覆写 `createVerifier()`），杜绝"框架静默退化回 connector 自洽验证"的漏洞。

### 2.3 verifier/ —— 旁路验证器体系（原则 1 的载体）

**ConnectorVerifier 接口**（11 个方法 + close）：

| 分组 | 方法 | 用途 |
|---|---|---|
| 数据验证 | `count` / `selectByPk` | 旁路 count、按主键取行（事实来源 = 对端库） |
| 结构准备 | `createTable` / `insert` | 旁路建表、旁路写数（不经过 connector） |
| DDL 锚点 | `tableExists` / `dropTable` / `tableColumns` | 表存在性、删表、列元数据（字段级 DDL 旁路验证） |
| 索引 | `listIndexes` / `createIndex` | 索引动作的旁路准备与验证 |
| 约束 | `listConstraints` / `createConstraint` | 约束动作的旁路准备与验证（无约束概念的数据源返回空） |

**VerifierFactory** —— 零配置自动装配：

```java
// 反射扫描 connector 实例字段（含继承链）：
//   io.tapdata.common.JdbcContext 子类 → JdbcVerifier
//   com.mongodb.client.MongoClient    → MongoVerifier（config 取 database 名）
// 均无 → null（由子类 createVerifier() 兜底）
```

全程**字符串类名反射**，tapdata-it 不持有 sql-core / mongodb-driver 编译依赖，任意 Connector 只要成员命名符合惯例即自动获得旁路能力。

**JdbcVerifier** —— RDBMS 旁路实现，关键设计：

- 反射链路：`Connector 实例 → JdbcContext 子类 → 私有字段 hikariDataSource → JDBC 直连`；
- `withAutoCommit` 包装：**引擎连接池 autoCommit=false，旁路写操作不强制自动提交会在连接归还池时被回滚**（MySQL 实测陷阱），旁路 DDL/DML 统一强制自动提交并恢复原状态；
- `qualifiedTable` / `qualifiedColumn` / `schemaPattern` 三个受保护方法可覆写（DB2 i 双引号 schema 限定）；
- `tableColumns` 走 JDBC 标准 `DatabaseMetaData.getColumns`（跨驱动通用，按下划线通配符陷阱按 TABLE_NAME 精确过滤）；
- `listIndexes` / `listConstraints` 默认查 `information_schema`（MySQL 系），非 information_schema 库覆写（DB2 i 用 QSYS2 系统目录）。

**MongoVerifier** —— MongoDB 旁路实现，关键设计：

- 一律基于**公开接口**反射（`com.mongodb.client.MongoClient/MongoDatabase/MongoCollection` 为 driver 导出包；反射实现类会被 Java 9+ 模块系统拒绝）；
- `createTable` = `createCollection`（MongoDB 无 DDL）；`tableExists` 用 `listCollectionNames` 精确匹配（避免 countDocuments 隐式建集合误判）；
- `createIndex` 注意 `Indexes.ascending` 是**变参**，反射签名必须用 `String[].class`；
- `tableColumns` / `listConstraints` 返回空、`createConstraint` 空操作（无 schema/约束概念，相关用例自动跳过）。

### 2.4 ConnectorTestContext —— 测试上下文

[ConnectorTestContext.java](../src/main/java/io/tapdata/it/ConnectorTestContext.java)：builder 模式承载被测 Connector 全部运行时要素（connector/nodeContext/connectionContext/functions/codecRegistry/config/log/verifier），并内置 **6 个数据库特性开关**，把"NoSQL 语义差异"收敛为配置而非用例改动：

| 开关 | 默认（RDBMS 语义） | MongoDB 场景 |
|---|---|---|
| `createTableReportsTableExists` | true | false（幂等建集合不报告已存在） |
| `schemaDiscoveryRequiresSampleData` | false | true（空集合无字段可推断，先旁路写采样数据） |
| `schemaAllowsExtraFields` | false | true（隐式 `_id` 字段） |
| `schemaPrimaryKeyStrict` | true | false（主键为库自动生成） |
| `executeCommandSupportsPing` | true | false（仅 execute/aggregate 类命令） |
| `fieldMinMaxRequiresPartitionIndex` | false | true（min/max 基于分区索引字段） |

### 2.5 schema/ —— 测试表与字段模型

- `TestDataType`：13 个通用类型枚举，与 PDK `TapType` 一一对应，是生成器选择与断言分派的中枢；
- `TestFieldSpec`：列语义描述——`dataType`（方言类型，如 `VARCHAR(255)`/`Int32`）、`testDataType`（通用语义）、`length/scale/precision`、`primaryKey/autoInc/nullable`、`fixedValue`（边界注入）；
- `TestTableSpec`：表名随机生成（`_tap_it_` + 时间戳 Base36 + 随机串，用例间隔离）+ 有序字段列表；`defaultAllTypesSpec()` 覆盖 11 种类型，TEXT/BLOB 为可选大对象（AS400 不支持，按需启用）。

### 2.6 generator/ —— 随机数据生成器体系

- `ValueGenerator<T>` 统一接口（`next()` / `getColumnName()`）；
- 14 个类型生成器（Int/Long/String/Text/Blob/Decimal/Float/Double/Bool/Date/DateTime/Timestamp/Sequence + BaseGenerator 抽象基类），值域受控（避免溢出、非法日期、精度丢失）；
- `RandomDataFactory`：主键列自动替换为 `SequenceGenerator`（1..N 可预测，update/delete 用例精确定位）；支持种子复现；`ThreadLocalRandom` 无锁竞争。

### 2.7 mapping/ —— TapTypeResolver（与引擎同源的类型解析）

```java
// 从 Connector 类的 @TapConnectorClass 注解读取 spec 文件名（如 spec_db2.json）
TapTypeResolver.from(connector.getClass());
// 方言 dataType → TapType：DefaultExpressionMatchingMap 表达式匹配（大小写不敏感）
resolver.resolve("varchar(255)");   // → TapString
resolver.isDeclared("FLOAT(4)");    // → true（spec 声明即通过）
```

**与引擎 wrap 链路同源**：引擎的 `TableFieldTypesGenerator.autoFill` 用同一份 spec dataTypes 将方言值包装为 TapXxxx 类型，因此集成测试**不再维护方言映射表**——spec 声明即事实，spec 缺失某类型时断言直接失败（反向约束 spec 完备性）。

### 2.8 asserts/ —— 类型等价比较断言

- `RecordAssert`：按列类型分派比较，容忍数据库存储/回读的类型等价差异——数值族 BigDecimal 归一化（decimal 固定 scale=4）；float/double 相对误差 1e-6；布尔兼容 `true/1/1.0`；日期族统一转 LocalDateTime(UTC) 并**四舍五入到秒**（MySQL timestamp(0) 列小数秒舍入存储行为）；字符串去尾部空白；
- `TableAssert`：字段存在性/顺序/类型族（TapType）/dataType 被 spec 声明/主键标记；宽松模式适配 NoSQL（允许额外字段、主键非严格）。

### 2.9 support/ —— 运行时支撑

`TestStateMap` / `TestTableMap`：内存 KVMap 实现，替代引擎的持久化 KVMap 供给 `TapConnectorContext`（部分 Connector 在 onStart/写记录/DDL 时依赖 stateMap/tableMap 存取元数据，缺失会导致 NPE 或行为退化——DB2 i 的 `timestampToStreamOffset` 依赖 tableMap 查系统表名拼 journal 过滤条件，tableMap 为空时 offset 退化为 0）。`TestJobContext`/`TestLog` 提供日志与任务上下文支撑。

## 3. 用例分组总览（A~J 十组，61 个用例）

| 组 | 覆盖能力 | 典型用例 | 旁路验证方式 |
|---|---|---|---|
| A 连接与元数据 | connectionTest / discoverSchema / getTableNames / dropTable / getTableInfo / checkTableName / getCharsets / connectionCheck | `should_discover_schema_of_created_table`、`should_table_not_found_after_drop` | 旁路建表 + `tableExists` 锚点 |
| B 表级 DDL | createTableV2 / clearTable / dropTable / alterTableCharset / alterTableTTL / alterDatabaseTimeZone | `should_create_table_v2`（+tableExists +count=0）、`should_clear_table` | 旁路建表/写数 + `tableExists`/`count` |
| C 数据读写（核心） | writeRecord / batchCount / batchRead / queryByFilter / queryByAdvanceFilter / afterInitialSync | `should_write_insert_records`（count+select 双确认）、`should_batch_read_data_consistent`（与旁路 select groundTruth 逐行比对） | 旁路 count + `selectByPk` |
| D 索引与约束 | createIndex / queryIndexes / deleteIndex / createConstraint / queryConstraints / dropConstraint | `should_delete_index`（旁路准备索引 → 被测删除 → 旁路 listIndexes 验证） | `listIndexes`/`listConstraints` |
| E 字段级 DDL | newField / dropField / alterFieldName / alterFieldAttributes | `should_alter_field_attributes`（size 扩为 500） | `tableColumns` |
| F 流式读取 | timestampToStreamOffset / streamRead / streamReadOneByOne / streamReadMultiConnection | `should_stream_read_incremental`（写前注册 tableMap → 旁路写增量 → 15s 窗口收齐 → 与旁路 groundTruth 逐字段比对） | `bypassInsert` + `selectByPk` |
| G 事务 | transactionBegin/Commit/Rollback | `should_transaction_commit`（commit 后旁路 count=5）、`should_transaction_rollback`（rollback 后旁路 count=0） | 旁路 `count` |
| H 命令 | executeCommand / executeCommandV2 / runRawCommand / countRawCommand / exportEventSql | `should_run_raw_command`（读回行数 == 旁路 count） | 旁路 `count` |
| I 其他 | getCurrentTimestamp / queryHashByAdvanceFilter / control / processControl / getStreamOffset / flushOffset / errorHandle / createPartitionTable / queryPartitionTablesByParentName / dropPartitionTable / countByPartitionFilter / getReadPartitions / queryFieldMinMaxValue / connectorWebsite / tableWebsite / commandCallback | `should_get_read_partitions`（consumer 至少收到 1 个分区）、`should_query_field_min_max`（min/max 与旁路 select 计算值比对） | 旁路 count / `selectByPk` / `tableExists` |
| J 能力覆盖校验（原则 3） | 全能力三集合自检：requiredCapabilities（声明）⊆ implementedCapabilities（实现）⊆ coveredCapabilities（覆盖） | `should_all_capabilities_implemented_and_covered`——不遗漏必实现接口 + 已实现（含未声明）必被测到 | 无（纯框架自检） |

## 4. 如何扩展（接入新 Connector 的分级指南）

### 4.1 一级：最小接入（MySQL 范式，~60 行）

```java
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class XxxConnectorIT extends ConnectorIT {
    @Override
    protected ConnectorTestContext createContext() throws Throwable {
        XxxConnector connector = new XxxConnector();
        DataMap config = readConnectionConfig("config/xxx-connection.json"); // 支持 -Dconnector.it.* / CONNECTOR_IT_* 覆盖
        TapConnectorContext nodeContext = new TapConnectorContext(
                loadSpecification("spec.json"), config, DataMap.create(), new TapLog());
        nodeContext.setStateMap(new TestStateMap());
        ConnectorFunctions functions = new ConnectorFunctions();
        TapCodecsRegistry codecRegistry = TapCodecsRegistry.create();
        connector.registerCapabilities(functions, codecRegistry);
        return ConnectorTestContext.builder()
                .connector(connector).nodeContext(nodeContext)
                .connectorFunctions(functions).codecRegistry(codecRegistry)
                .config(config).log(new TapLog()).build();
    }
}
```

子类还必须覆写 `requiredCapabilities()` 主动声明本 connector 必实现的接口（按角色组合 `SOURCE_ROLE_CAPABILITIES` / `TARGET_ROLE_CAPABILITIES` 常量，再追加特有能力，见 4.6）；基类校验用例自动保证“不遗漏必实现接口、已实现必被测到”。只要 Connector 实例持有 `JdbcContext` 子类或 `MongoClient` 成员，旁路验证器自动装配，其余全部由基类驱动。

### 4.2 二级：特性开关适配（MongoDB 范式）

在 `createContext()` 中按数据源语义设置 6 个特性开关（见 2.4），或覆写 `beforeWrite()` 钩子（如 MongoDB 保证 `c_int` 唯一以满足唯一索引用例）。**用例代码零改动**。

### 4.3 三级：方言适配（DB2 i 范式）

| 扩展点 | 场景 | DB2 i 示例 |
|---|---|---|
| `createTestTableSpec()` | 方言类型差异 | FLOAT→REAL/DOUBLE、BOOLEAN→SMALLINT、DATETIME→TIMESTAMP，关闭大对象 |
| `createVerifier()` | 标识符引用/系统目录差异 | 匿名 JdbcVerifier 覆写 `qualifiedTable`/`qualifiedColumn`（双引号 schema 限定）、`schemaPattern`、`listIndexes`/`listConstraints`（QSYS2.SYSINDEXES/SYSCST）、索引/约束名双引号限定 |
| `rawQueryCommand()` / `rawCountCommand()` | 原始命令方言 | `select * from "SCHEMA"."TABLE"` |

### 4.4 四级：无法自动发现的验证器

文件/消息类 Connector 无 JdbcContext/MongoClient 成员时，覆写 `createVerifier()` 提供专属 `ConnectorVerifier` 实现（直连其存储后端）；`supportsTableDDL()` 返回 false 时 B 组用例自动跳过。

### 4.5 自定义用例

在子类中新增 `@Test` 方法，直接使用基类工具方法（`prepareData`/`bypassInsert`/`verifyCount`/`verifyRowsByPk` 等）；涉及数据验证的用例必须标注 `@UnderTest(value="能力名", requiresVerifier=true)`。

### 4.6 扩展点总表

| 扩展点 | 默认行为 | 定制场景 |
|---|---|---|
| `createContext()`（abstract） | — | 提供 Connector/上下文/能力注册表/配置 |
| `createTestTableSpec()` | 全通用类型 | 方言类型、专属字段、去掉不支持类型 |
| `enableOptionalLargeObjectTypes()` | false | 支持 CLOB/BLOB 时启用 TEXT/BLOB 字段 |
| `createTypeResolver()` | 从 `@TapConnectorClass` 自动加载 | 缺失注解/spec 时定制 |
| `createVerifier()` | VerifierFactory 自动发现 | 专属旁路验证器 |
| `defaultRecordCount()` / `tablePrefix()` | 100 / `_tap_it_` | 数据量、前缀定制 |
| `supportsTableDDL()` | true | 文件/消息类无表概念 |
| `resolveTestTable()` | spec 表名 | 预建表场景 |
| `rawQueryCommand()` / `rawCountCommand()` | 裸表名 SQL | 方言限定 |
| `assertSchema()` | TableAssert 通用断言 | 无 schema 概念整体定制 |
| `beforeWrite()` | 原样返回 | 构造依赖数据/约束/唯一化 |
| `requiredCapabilities()` | 当前已注册的全部接口实现（implementedCapabilities()） | 按角色（源/目标）收窄声明必实现接口，框架校验不遗漏 + 已实现必被覆盖 |
| `ignoredCapabilities()` | releaseExternal / memoryFetcher / memoryFetcherV2 | 排除引擎生命周期钩子等非业务能力 |

## 5. 设计优势（重点）

### 5.1 打破自洽验证，测试结果可信（原则 1）

传统集成测试"用 Connector 的 batchRead 验证 Connector 的 writeRecord"存在逻辑漏洞：**读写双方实现同一个 bug（如时区偏移、类型截断）时，读回值与期望"一致"，但都是错的**——用例全绿却掩盖了真实缺陷。本框架的验证事实来源是**对端数据库直连结果**：

- 写后验证：`bypassInsert` 后旁路 `count` + `selectByPk` 逐字段比对（`verifyRowsByPk`）；
- 读后验证：`batchRead`/`streamRead`/`queryByFilter` 的结果与旁路 `selectByPk` groundTruth 比对，而非与生成器期望值比对；
- 结构验证：建表/删表/字段 DDL/索引/约束全部以旁路 `tableExists`/`tableColumns`/`listIndexes`/`listConstraints` 为锚点。

旁路验证器通过**反射复用 Connector 自身的连接资源**（HikariDataSource/MongoClient），同一份连接配置、零额外部署，却能提供与 Connector 完全无关的独立视角。

### 5.2 杜绝降级绕过，防"假绿"（原则 3）

历史上最隐蔽的漏洞是**静默退化**：验证器不可用时框架悄悄退回 connector 自洽路径，用例照样"通过"。本框架的强约束消除了所有绕过通道：

- `@UnderTest(requiresVerifier=true)` 无验证器 → **fail**（setUp 兜底），提示实现 `createVerifier()`；
- `createTableIfNeeded` 无验证器且支持 DDL → **fail**（禁止降级到 connector createTableV2）；
- 声明的必实现能力未实现 → **fail**（declared but not implemented）；已实现能力（含未声明）无用例覆盖 → **fail**（implemented but not covered）——框架级校验用例 `should_all_capabilities_implemented_and_covered` 双保险兜底，跳过不再是"默认安全"。

### 5.3 声明式三态，报告语义精确

`requiredCapabilities()` 把 connector 的必实现接口清单**显式编码在代码里**，`@UnderTest` 把每个用例的意图（被测哪个能力、是否必须旁路）同样显式编码；`require()` 的三态判定（FAIL/SKIP/TEST）使测试报告能精确区分"connector 未实现该能力（跳过，符合预期）"与"声明了却没实现（失败，回归信号）"，框架级校验用例则保证已实现能力（含未声明）全部有覆盖——61 个用例的通过/失败/跳过统计可直接反推 connector 的能力覆盖矩阵。

### 5.4 零配置自动装配，接入成本极低

`VerifierFactory` 反射扫描成员按类型自动装配（JdbcContext→JdbcVerifier、MongoClient→MongoVerifier），全程字符串类名——**tapdata-it 不依赖 sql-core、mongodb-driver、任何具体 Connector**。新增 Connector 的最小接入只有 `createContext()` 一个方法（MySQLConnectorIT 仅 59 行），旁路能力自动获得；无法自动发现的场景由 `createVerifier()` 覆写点兜底，扩展路径清晰。

### 5.5 特性开关 + 钩子，NoSQL 适配不侵入用例

6 个数据库特性开关把 MongoDB 等 schema-free 数据源的语义差异（隐式 `_id`、幂等建集合、采样数据发现、索引驱动 min/max）收敛为**配置项**，而不是给每个用例加 if-else 或复制用例——同一份用例在 MySQL（严格断言）与 MongoDB（宽松断言）上同时成立，用例代码零分叉。

### 5.6 与引擎同源，测试路径即生产路径

- 类型解析：`TapTypeResolver` 直接用 connector spec.json 的 `dataTypes`（与引擎 `TableFieldTypesGenerator.autoFill` 同款解析），**不维护独立方言映射表**——spec 声明即事实，测试同时反向校验 spec 完备性；
- 生命周期：`init → 能力调用 → releaseExternal → stop` 与引擎 PdkNode 一致；
- 上下文：DML 策略（JUST_INSERT/IGNORE_ON_NON_EXISTS）、tableMap 注册（流式用例写前注册，模拟引擎建表后行为）、codec 转换（`transformFromTapValueMap`）均与引擎一致——**在测试里发现的问题，就是引擎里会发生的问题**（DB2 i streamRead 的 tableMap 依赖正是由此暴露）。

### 5.7 工程健壮性

- **隔离**：表名随机 + 每次用例独立建表 + tearDown 兜底删除（断言失败也执行）；
- **资源**：tearDown 保证 `releaseExternal`+`stop`，堆内存边界打印跟踪泄漏；
- **陷阱修复**：`withAutoCommit` 规避引擎连接池 autoCommit=false 回滚（MySQL 实测数据丢失）；MongoDB 反射只走公开接口（规避 Java 9+ 模块系统）；变参反射签名（`String[].class`）；
- **版本兼容**：按 pdk-api 2.0.5 编译 + `reflectFunction` 反射处理新能力（alterTableTTL/processControl），低版本环境自动跳过；
- **可复现**：生成器支持种子；`RecordAssert` 类型等价比较（秒级舍入、相对误差、布尔兼容）消除存储差异导致的误报；
- **可观测**：每个用例/工具方法输出耗时、行数、堆内存、验证器类型，失败信息含列名/期望/实际/类型。

## 6. 已知边界与注意事项

1. **旁路验证器覆盖面**：自动发现仅覆盖 JdbcContext/MongoClient 两种成员形态；其他数据源需子类 `createVerifier()`，`requiresVerifier=true` 用例会强制拦截遗漏；
2. **旁路 SQL 方言**：JdbcVerifier 默认 `information_schema` 目录与裸标识符，非 MySQL 系数据库需覆写（DB2 i 已示范）；
3. **约束语义差异**：connector `queryConstraints` 的语义（如 CommonDbConnector 只查外键）与旁路 `listConstraints`（查全部约束）可能不一致，此类失败是 connector 实现问题而非测试问题，需在报告中区分；
4. **流式用例**：依赖真实 CDC 环境（binlog/journal），旁路写入确认落库后收不到增量多为环境/实现基线问题，可用 A/B 实验（改回 writeInsertEvents）确认；
5. **执行方式**：`mvn -pl <connector> test-compile failsafe:integration-test failsafe:verify -Dit.test=XxxConnectorIT -DskipITs=false -o`；连接配置经 `-Dconnector.it.*` 或 `CONNECTOR_IT_*` 注入，敏感信息不入库。
