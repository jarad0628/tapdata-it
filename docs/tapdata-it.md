
一、测试痛点与 3 阶段总体思路

1.1 文档目的

本文档面向 TapData 工程团队，核心目的有三：

1. **阐述当前产品发版及测试的痛点**，提出借助 AI 能力构建切实可行的落地方案；
2. **定义各层测试用例的关注焦点**，指导开发过程中如何确定测试用例范围，以及测试用例的编写规范；
3. **讲解如何通过一层层的测试和 CI 流程拦截产品缺陷**，发布生产可用稳定版本。

本工程的发起目的是**保障迭代版本功能正确**：TapData 以 2 周为一个迭代周期，通过自动化测试确保每次发版的新功能和老功能都正确。

1.2 TapData 测试痛点

TapData 是典型的大型多模块、插件化数据集成/实时 CDC 引擎平台，代码分布在 tapdata（引擎与控制面）、tapdata-common-lib（插件 SDK）、tapdata-connectors（开源连接器）、tapdata-connectors-enterprise（企业连接器）、tapdata-enterprise（企业组件）等多个仓库。在 2 周一迭代的快节奏下，面临三大核心测试痛点：

1. **组合爆炸与环境依赖重**：支持数十种异构数据库（开源连接器 68 个、企业连接器 18 个、国产库连接器 8 个），CDC 场景需要特定的物理机制配置（MySQL Binlog、PostgreSQL WAL、MongoDB Oplog）。单靠人工搭建或常驻静态测试库，环境维护成本极高。
2. **异步链路与时序延时高**：实时数据流处理依赖事件驱动（`TapEvent` 流、Hazelcast 分布式节点、ShareCdc 共享读取），传统断言容易因网络抖动或处理延迟导致伪失败（Flaky Tests）。
3. **架构解耦带来的契约模糊**：执行引擎与 Connector 插件通过 PDK（`tapdata-pdk-api`）隔离，跨仓库、跨语言（Java 引擎 / Node.js 组件 / Python 测试工程），新成员或开发人员编写单测时难以快速厘清模块间交互契约，导致单测质量参差不齐。

1.3 核心思路：3 个阶段逐步构建自动化测试

+----------------------------------------------------------------------------------------+
|  阶段 1：构建 AI 协同知识库                                                               |
|  (物理组件设计与实现架构 + 接口契约 + 对外服务能力与核心工作流 + 单元/集成测试规范)              |
+----------------------------------------------------------------------------------------+
                                         |
                                         v
+----------------------------------------------------------------------------------------+
|  阶段 2：结合现有测试用例，补齐 L0、L1、L2、L3 各层缺失测试用例                                |
|  L0 单元与契约测试 -> L1 物理模块集成测试 -> L2 系统服务组件测试 -> L3 系统集成测试(黑盒)       |
+----------------------------------------------------------------------------------------+
                                         |
                                         v
+----------------------------------------------------------------------------------------+
|  阶段 3：集成 CI/CD 流程                                                                  |
|  (定期全量执行自动化测试 + 每次修改增量执行自动化测试 + 测试报告输出)                          |
+----------------------------------------------------------------------------------------+

**测试金字塔分层定位：**

          / \
         /   \       L3: 系统集成测试 / E2E，关注点：HA、Failover、生产稳定性
        /     \      ----------------------------------------------------------------------------------
       /       \     L2: Java 组件级集成测试（Spring Test / MockService 内存级交互 + 轻量外部依赖），关注点：模块与模块交互
      /         \    ----------------------------------------------------------------------------------
     /           \   L1: 物理模块集成测试（JUnit5 + AssertJ + Testcontainers），关注点：单独的模块功能
    /             \  ----------------------------------------------------------------------------------
   /               \ L0: 单元与契约测试 ( JUnit 5, Mockito )，关注点：代码逻辑，接口约定等

**核心原则：**
- 越底层越轻量、解耦和快速
- 测试左移（Shift Left）：问题越早发现，修复成本越低
- 谁开发，谁负责（Dev Ownership）：开发人员需对其提交代码的 L0/L1 层自动化测试质量直接负责；L2/L3 由 QA/SDET 主导
- 自动化优先于手动：重复性回归测试 100% 自动化。

---

二、阶段 1：构建 AI 协同知识库

要让 AI 工具（Cursor、Claude Code、GitHub Copilot 等）高效辅助团队编写合格的单测与集成测试，必须将现有代码库中的隐性架构规则转化为 AI 能够理解的**显性 Context**。知识库内容组成如下：

- 总体架构模块列表及说明
- 物理组件设计与实现架构、接口契约；
- 对外提供的服务能力及其核心工作流程和外部依赖关系（任务调度服务、Connector 插件服务、通知告警服务、类型映射系统等）；
- 代码规范（单元测试规范、集成测试规范）。
2.1 知识库结构定义与敏捷化建设方式

> 关键认知（避免“鸡生蛋”悖论）：知识库先定义结构和内容组织方式，利用 AI 工具批量生成初稿，再由研发人工校正，校正过程本身就是团队对架构达成共识的过程，随代码和单测增量演进产出。团队当前正是因缺乏架构契约和规范才导致单测质量参差不齐，不要求先补齐几十份架构设计文档再动手写测试，避免启动阻塞。

**知识库建设流程（五步，可循环）**：

① 定义知识库结构  ② AI 工具统一生成初稿  ③ 全体研发人工校正  ④ 定义测试规范与模板  ⑤ AI 生成测试用例 + 人工校准
   （目录/模板/粒度）     （基于模块代码与依赖       （校正准确后版本化，        （单测规范/集成测试规范/        （基于测试模板 + 知识库文档
                         分析批量生成，1-2 周内）    作为 AI Context 基线）      测试用例模板，随 2.4 落地）       + 模块代码生成，逐模块推进）

1. **第一步（启动）**：定义知识库目录结构与内容模板（见下），明确每个文档的粒度与必填项，**不要求一次补齐**；
2. **第二步（AI 批量生成）**：利用 AI 工具基于 Maven 模块依赖分析、代码结构与注释，批量生成全部文档初稿（含服务能力工作流、接口契约），预计 1-2 周内完成首轮；
3. **第三步（人工校正）**：发动所有研发按模块认领校正，修正 AI 生成与代码不符之处——校正过程即架构评审；校正准确后的知识库版本化发布，作为 AI 编写测试的 Context 基线；
4. **第四步（规范先行）**：与校正并行，定义并发布单元测试规范与集成测试规范及测试用例模板（本指南 2.4），作为 Code Review 检查项；
5. **第五步（AI 生成测试）**：知识库校正准确后，基于测试模板 + 知识库文档 + 模块代码，利用 AI 编写 L0/L1 测试用例，开发人员人工校准后入库。

在独立仓库 docs/ 下建立统一的 AI 协同配置与知识沉淀文件：

tapdata-wiki/
└── docs/
    ├── 架构设计
    │   ├── 数据流设计
    │   ├── 执行面架构
    │   ├── 插件化架构
    │   ├── 通信架构
    │   ├── 控制面架构
    │   ├── 整体架构概览.md
    │   └── 架构设计.md
    ├── 最佳实践
    │   ├── 最佳实践.md
    │   ├── 团队协作最佳实践.md
    │   ├── 作业设计最佳实践.md
    │   ├── 性能优化最佳实践.md
    │   ├── 运维管理最佳实践.md
    │   └── 安全配置最佳实践.md
    ├── 核心模块
    │   ├── 并发顺序处理框架.md
    │   └── 核心模块.md
    ├── 数据库设计
    │   ├── 数据库模式设计
    │   ├── 核心数据模型
    │   ├── 数据访问模式.md
    │   ├── 数据验证规则.md
    │   └── 数据库设计.md
    ├── 开发指南
    │   ├── 构建流程.md
    │   ├── 测试策略.md
    │   ├── 调试与故障排查.md
    │   ├── 代码规范.md
    │   ├── 数据库测试工具集.md
    │   ├── 开发环境搭建.md
    │   ├── 开发指南.md
    │   └── 贡献流程.md
    ├── 项目概述
    │   ├── 快速开始
    │   ├── 核心功能特性
    │   ├── 项目概述.md
    │   ├── 架构概览.md
    │   ├── 项目简介.md
    │   └── 技术栈概览.md
    ├── 插件系统
    │   ├── 连接器插件
    │   ├── 转换器插件.md
    │   ├── 插件管理.md
    │   ├── 插件系统.md
    │   ├── 插件开发指南.md
    │   ├── 插件API设计.md
    │   └── DAG扩展框架.md
    ├── 部署运维
    │   ├── 监控告警
    │   ├── 部署方式
    │   ├── 部署运维.md
    │   ├── 环境准备.md
    │   ├── 备份恢复.md
    │   ├── 故障排查.md
    │   └── 性能调优.md
    ├── 快速开始.md
    ├── 故障排查.md
    └── API参考文档
        ├── 认证与授权.md
        ├── API参考文档.md
        ├── REST API接口
        ├── SDK使用指南
        └── WebSocket API接口

注：
1. 知识库独立在一个仓库维护，在团队之间共享。
> 2. 上表目录为目标结构模板，按“建设流程”第二步由 AI 批量生成初稿，按需增量填充，严禁阻塞测试工作启动；优先保证 `架构设计`、`开发指南/测试策略`、`插件系统` 三类文档校正到位。

2.2 物理组件设计与实现架构、协助契约

列举所有组成模块（可以使用 maven module依赖分析出所有的模块）

2.3 对外服务能力、核心工作流程与外部依赖关系

AI 编写测试前必须理解"这个服务对外承诺了什么、依赖了什么"，否则生成的用例会偏离真实行为。如下 4 个服务：

2.3.1 任务调度服务

manager/tm（schedule、scheduleTasks）                iengine-app（flow.engine.V2）
+----------------------------+   下发任务    +-----------------------------------+
| 定时/周期调度触发            | ------------> | task 创建 -> DAG 编译 -> 节点启动    |
| 任务状态机（taskhistory）    | <------------ | （source/pdk/processor/target）    |
| 状态回报与监控（monitor）    |   状态/进度    | Hazelcast 分布式执行 / ShareCdc     |
+----------------------------+               +-----------------------------------+

- 外部依赖：MongoDB（TM 元数据存储）、Hazelcast（集群协调）、消息队列（TM 与 Engine 通信）、各数据库（连接器执行）。
- 测试关注：调度触发准确性、状态机流转（新建→运行→停止→错误）、断点续传、并发调度互斥。
2.3.2 Connector 插件服务（PDK 生命周期）

连接测试(connectionTest) -> 表发现(discoverSchema) -> 表数量(tableCount)
     -> 全量读取(BatchRead) -> 增量读取(StreamRead)     -> 写入(WriteRecord)
     -> 建表/清表/删表(CreateTable/ClearTable/DropTable) -> 释放(ReleaseExternal)

- 接口契约：见 2.2 的 TapConnectorNode 与 ConnectorFunctions 函数清单。
- 测试关注：每个函数在真实数据库上的行为符合预期（L1），多个函数组合成完整同步链路（L3/E2E）。
2.3.3 通知告警服务

告警事件产生（任务异常/延迟/数据校验失败等，manager/tm/alarm）
   -> 规则匹配（alarmrule：阈值、条件、级别）
   -> 渠道发送（alarmMail：邮件等）
   -> 前端展示与用户处理

- 测试关注：告警事件触发准确性、规则匹配（阈值边界）、渠道发送成功、重复告警抑制。
2.3.4 类型映射系统

数据库原生类型（MySQL VARCHAR / Oracle NUMBER / MongoDB BSON 等）
   -> 引擎侧映射（iengine-app typemapping：数据库类型 <-> TapType）
   -> 连接器侧编解码（TapCodecsRegistry：TapType <-> Java 类型）
   -> 目标端建表类型（CreateTable 时反向映射）

- 测试关注：映射精度（高精度数值、时区、Date/TimestampTZ、BLOB/CLOB）、边界值（NULL、NaN、溢出）、双向映射一致性。manager/tm/typemappings 有独立的 service/controller 层，可直接做 L0/L2 测试。
2.4 代码规范（单元测试规范 / 集成测试规范）

规范必须落到仓库中的 AI 配置，让 AI 生成的第一版代码即符合团队标准。

2.4.1 单元测试规范（L0）

1. 框架：JUnit 5（org.junit.jupiter）+ Mockito + AssertJ（链式断言）+ ReflectionTestUtils（触发私有 registerCapabilities 等）。
2. 命名：测试类以 Test 结尾（如 MysqlConnectorTest.java），集成测试以 IT 结尾（如 MysqlConnectorBatchReadIT.java）。
3. 结构：AAA（Arrange-Act-Assert），每个用例只验证一个行为。
4. 异步处理：数据流/事件类断言**严禁使用 `Thread.sleep()`**，统一使用 Awaitility 轮询断言。
5. 边界：覆盖 null、空集合、极长字符串、数值溢出、非法参数、Mock 依赖抛 RuntimeException 的容错。
6. 隔离：Mock 掉所有外部依赖（数据库、网络、Hazelcast），测试不依赖任何真实环境。
7. 清理：测试结束重置静态变量、关闭句柄（@AfterEach/@AfterAll）。
2.4.2 集成测试规范（L1）

1. 框架：JUnit 5 + AssertJ + Testcontainers（动态拉起真实数据库容器，配置 CDC 参数）。
2. 定位：测试**物理模块与其外部依赖**的真实交互，通过模块提供的 runtime 按接口契约调用各方法，验证结果与行为符合预期。
3. 管理：与 L0 一样放在代码库 src/test 中管理，使用 mvn verify 发起执行，确保每次修改提交时执行相关测试。
4. 命名：以 IT 结尾，Surefire 与 Failsafe 插件配合（mvn test 跑单测、mvn verify 跑集成测试）。
5. 幂等：测试数据使用唯一前缀/随机后缀（参考 t-layer3-test 的 S() 命名机制），用例结束清理数据。
6. 不引入静态共享库：禁止使用常驻共享数据库（数据污染与并行冲突），一律动态拉起、用完销毁。
7. 超时与重试：外部服务波动导致的超时按 Flaky 治理流程处理（见 4.4），不允许通过放宽断言掩盖问题。
2.4.3 测试规范示例（可直接作为 AI 提示词模板）

# TapData AI Coding & Testing Rules

## Architecture Contracts
- Engine (tapdata/iengine) and Connectors (tapdata-connectors) must stay decoupled via
  tapdata-pdk-api (io.tapdata.pdk.apis). Never import database drivers into engine core.
- Connector must implement TapConnectorNode: discoverSchema / connectionTest / tableCount,
  and register capabilities via registerCapabilities(ConnectorFunctions, TapCodecsRegistry).
- 数据库类型映射统一走 typemappings / TapCodecsRegistry，禁止在业务代码中硬编码方言类型.

## L0 Unit Test Requirements
1. Frameworks: JUnit 5, Mockito, AssertJ, ReflectionTestUtils; class name ends with Test.
2. AAA structure; one behavior per test; cover null/empty/overflow/exception paths.
3. Async: use Awaitility.await() for stream/event assertions. Never use Thread.sleep().
4. Mock all external dependencies; no real DB/network in L0.

## L1 Integration Test Requirements
1. JUnit 5 + Testcontainers; class name ends with IT; run via `mvn verify`.
2. Test by calling PDK functions (connectionTest / discoverSchema / batchRead / streamRead /
   writeRecord / createTable ...) and assert results match contract expectations.
3. Clean up tables/containers after each test; use unique test data names.


---

三、阶段 2：结合现有测试用例补齐 L0-L3

3.1 现有测试资产盘点（现状基线）

**L0 单元测试（Java，按仓库统计）：**

仓库 / 模块
活跃测试类数量
说明
tapdata/manager/tm
约 194
另有 src/test/bak 归档旧测试；主要集中在 service 层
tapdata/iengine/iengine-app
156
含 websocket handler、数据流测试等
tapdata/iengine/iengine-common
53
事件实体、工具类
tapdata/iengine/api
9
Aspect、接口契约
tapdata-common-lib/plugin-kit/tapdata-api
7
数据模型
tapdata-common-lib/plugin-kit/tapdata-pdk-api
1
契约测试，明显不足
tapdata-common-lib/plugin-kit/tapdata-pdk-runner
5
Runner 行为
tapdata-connectors/connectors-common
少量
StringKitTest、ConnectorBaseTest 等
tapdata-connectors/connectors（开源）
约 172
huawei 31、mongodb 20、mysql 10、doris 10、postgres 8、es 5、redis 6 等；部分连接器仍为 0 测试

**L1 集成测试工具（标准工具链）**：

- 标准工具：JUnit 5 + AssertJ + Testcontainers，随代码库 src/test 管理，mvn verify 执行（见 3.3）。
- 存量调试工具（**不纳入标准 L1 工具链，不接入 CI 门禁**）：`tapdata-connectors/connectors-tdd`（PDK-TDD 命令化测试）与 `tapdata/tapdata-test`（独立 Connector runtime）。两者功能与 Testcontainers 重叠，保留用于本地临时调试，新用例一律使用 Testcontainers 标准方式。

**L3 系统级测试 / E2E（已有，可复用）：**

- t-layer3-test（Taptest）：./run build|start 构建部署完整环境；./run start db mysql 管理测试数据库；./taptest run -t=1.1 -f=config.yaml 运行用例；nightly.sh 按用例编号编排全量回归；auto_test/items 下 61 组用例（1_basic_dummy、g2_basic、3_mysql、4_mongo、6_oracle、g19_db2、g25_oceanbase_oracle 等），case_type 分 f（功能）/p（性能）/s（冒烟）。
3.2 L0：单元与契约测试

**定位**：代码级防御机制，毫秒级反馈。**关注点在代码逻辑层面**：纯逻辑演算、算法正确性、数据结构转换、条件分支、异常路径、PDK 接口契约。

**工具链**：JUnit 5 + Mockito + AssertJ + ReflectionTestUtils。

**用例范围确定方法**：按模块职能清单确定（参考 2.2 组件表）——

- 引擎（iengine）：DAG 编译、节点处理逻辑、事件转换、offset 推进、类型映射、异常封装；
- 控制面（manager/tm）：service 层状态流转、规则计算（告警阈值、调度表达式）、DTO/VO 转换、权限判定；
- plugin-kit：TapEvent/TapTable 模型行为、TapCodecsRegistry 编解码、PDK 契约（registerCapabilities 注册的函数集合必须与非空断言校验）；
- 连接器（connectors）：配置解析、SQL 生成（CommonSqlMaker）、类型映射、offset 解析（如 MysqlBinlogPosition）、异常分类（ExceptionCollector）。
**补齐策略（按优先级）**：

1. tapdata-pdk-api 契约测试（当前仅 1 个，严重不足）；
2. 连接器中 0 测试的高风险 CDC 连接器（含企业版 DB2/Oracle/Sybase）；
3. manager/tm-api、tm-common 公共逻辑；
4. iengine 引擎节点级（node/hazelcast/data|processor）逻辑测试。
**示例（真实代码风格，取自 `MysqlConnectorTest`）**：

@ExtendWith(MockitoExtension.class)
class MysqlConnectorCapabilityTest {

    @Test
    @DisplayName("PDK 契约：MySQL Connector 必须注册 hash 查询能力")
    void testRegisterCapabilitiesQueryTableHash() {
        // Arrange
        MysqlConnector connector = new MysqlConnector();
        ConnectorFunctions connectorFunctions = new ConnectorFunctions();
        TapCodecsRegistry codecRegistry = new TapCodecsRegistry();

        // Act：registerCapabilities 为私有方法，通过 ReflectionTestUtils 触发
        ReflectionTestUtils.invokeMethod(connector, "registerCapabilities",
                connectorFunctions, codecRegistry);

        // Assert：按 PDK 契约断言能力已注册
        assertThat(connectorFunctions.getQueryHashByAdvanceFilterFunction()).isNotNull();
    }
}

### 3.3 L1：物理模块集成测试
**定位**：物理模块的集成测试，**按照组件以及组件的依赖完成集成测试**。例如 MySQL Connector：直接提供一个加载运行 MySQL Connector 的 runtime，然后**根据接口契约调用各个方法，验证结果和行为符合预期**。关注点在**模块实际行为符合预期**（不是内部逻辑，而是对外契约行为）。

**工具链**：JUnit 5 + AssertJ + Testcontainers 等；不同的模块外部依赖及测试工具根据实际场景选择（消息队列可用 Testcontainers 或嵌入式；CDC 数据库必须真实容器并预置 Binlog/WAL/Oplog 配置）。

**执行方式**：L1 集成测试同 L0 一样放在代码中管理（`src/test`），使用 `mvn verify` 发起执行，确保每次修改提交时执行相关测试。

**用例范围确定方法**：以 PDK 函数清单为骨架（2.2 接口契约），每个函数一个用例组：

契约函数
验证内容
connectionTest
正确/错误凭据、权限不足、网络不可达、超时
discoverSchema
表发现、字段类型映射、表过滤条件
batchRead
全量读取条数、字段值精度、分页 batch 大小、offset 续读
streamRead
增量 DML 事件（Insert/Update/Delete）、DDL 事件、断线重连、offset 重置
writeRecord
写入成功、幂等、事务提交/回滚、主键冲突
createTable/clearTable/dropTable
DDL 执行结果、字段类型映射正确性

**运行方式：Testcontainers 自动环境（随 `mvn verify` 执行，L1 唯一标准方式）**

@Testcontainers
class MysqlConnectorBatchReadIT {
    // 动态拉起预置 Binlog=ROW 的 MySQL 容器
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("tapdata_it")
            .withUsername("root")
            .withPassword("password");

    @Test
    @DisplayName("L1：按 PDK 契约调用 batchRead，验证全量数据行为符合预期")
    void testBatchReadShouldReturnAllRows() throws Throwable {
        // Arrange：构建 Connector 上下文（连接信息来自容器）
        MysqlConnector connector = new MysqlConnector();
        TapConnectorContext context = buildContext(MYSQL);   // 测试辅助方法
        prepareTable(MYSQL, "orders", 100);                  // 造数
        TapTable table = new TapTable("orders");
        List<TapEvent> received = new ArrayList<>();

        // Act：注册能力后，按 PDK 契约调用 BatchReadFunction
        ConnectorFunctions connectorFunctions = new ConnectorFunctions();
        connector.registerCapabilities(connectorFunctions, new TapCodecsRegistry());
        connectorFunctions.getBatchReadFunction()
                .batchRead(context, table, null, 10, (events, offset) -> received.addAll(events));

        // Assert：行为符合预期（异步数据，使用 Awaitility 轮询断言）
        Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(received).hasSize(100));
    }
}

注：`connectors-tdd` 与 `tapdata-test` 为存量调试工具，功能与 Testcontainers 重叠，不纳入 L1 标准工具链与 CI 门禁；新用例统一使用上方 Testcontainers 方式（L1 唯一标准）。

**补齐策略**：优先为变更频繁、CDC 机制复杂、客户量大的连接器补齐 `*IT`（MySQL、PostgreSQL、MongoDB、Oracle、Kafka、Redis）；公共库 `connector-core`/`mysql-core`/`postgres-core` 的 CDC 核心逻辑优先补齐（被数十个连接器复用，单点收益最大）。

3.4 L2：Java 组件级集成测试（模块与模块交互）

**定位**：比 L1 更上一层的集成测试，**关注点在模块与模块之间的交互**。L2 是基于 Mock 或轻量 In-Memory / Spring Test 的 **Java 组件级集成**，验证模块组装后对外服务能力符合预期（如：调用告警服务新增一个告警事件，验证能够按照预期规则匹配并触发发送）。

**与 L1 / L3 的边界**：

- L1 验证**单个物理模块**与外部依赖的契约行为（真实数据库容器）；
- L2 验证**模块与模块交互**，在 Java 内存级上下文（Spring Context）内完成，**不需要构建部署整套产品**，保持测试金字塔“越底层越轻量、解耦和快速”的原则；
- 需要真实部署多组件的场景（如 `MySQL connector -> engine -> Oracle connector` 全链路）不属于 L2，**统一划入 L3/E2E** 执行（见 3.5）。

**工具链**：Spring Boot Test / MockMvc（`@SpringBootTest`、`@WebMvcTest`）+ Mockito（`@MockBean`/`@MockitoBean` 模拟下游依赖）+ 轻量外部资源（H2 / Embedded MongoDB / Embedded Kafka，必要时 Testcontainers 单一依赖）。

**执行方式**：与 L0/L1 一样放在代码库 `src/test` 中管理，使用 `mvn verify` 发起执行；在**提交 PR 时**执行（见 4.2）。

**用例范围确定方法**：从产品服务能力清单出发（2.3），每个跨模块交互场景一个用例：

交互场景
用例示例（内存级）
告警服务
调用告警服务新增告警事件，验证 alarmrule 规则匹配（阈值边界）与发送动作被触发
任务调度服务
创建定时任务，验证 schedule 触发逻辑与任务状态机流转（taskhistory）
数据校验服务
调用 inspect service 与 mock 数据源交互，验证差异结果计算准确
类型映射服务
typemappings service 数据库类型 ↔ TapType 双向映射一致性
引擎节点交互
iengine 处理器节点与 pdk 节点上下文交互（mock 输入事件流，验证处理输出）

**示例（告警服务，Spring Test + MockMvc，真实路由）**：

@SpringBootTest
@AutoConfigureMockMvc
class AlarmApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("L2：API 新增告警事件，验证按规则匹配")
    void testAddAlarmMsgThenList() throws Exception {
        // 1. 预置告警规则（内存级 Spring 上下文，真实路由 POST /api/alarm_rule/save）
        mockMvc.perform(post("/api/alarm_rule/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"task_error\",\"level\":\"critical\",\"threshold\":1}"))
                .andExpect(status().isOk());

        // 2. 新增告警事件（真实路由 POST /api/alarm/addMsg）
        mockMvc.perform(post("/api/alarm/addMsg")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"task_error\",\"taskId\":\"t_001\",\"message\":\"task failed\"}"))
                .andExpect(status().isOk());

        // 3. 查询任务告警列表，验证规则匹配生效（真实路由 POST /api/alarm/list_task）
        mockMvc.perform(post("/api/alarm/list_task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":\"t_001\"}"))
                .andExpect(jsonPath("$$.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$$[0].level").value("critical"));
    }
}

> 说明：TM 为 Spring Boot 应用，`@SpringBootTest` 拉起内存级上下文，MongoDB 等外部依赖通过嵌入式实例或 Testcontainers 单一轻量依赖提供；邮件等下游渠道用 `@MockBean` 模拟。

### 3.5 L3：系统集成测试 / E2E（黑盒）
**定位**：**将产品所有组件部署起来，执行黑盒测试，验证系统行为符合预期**——HA、Failover 以及各种生产环境稳定性相关的测试。关注点在**系统层面**。

> E2E 归属说明：所有需要完整部署才能验证的跨模块场景统一划入本层，包括跨库同步链路（`MySQL connector -> engine -> Oracle connector`，对应 `g25_oceanbase_oracle`、`g19_db2` 等同族用例）、通过真实服务 API 触发的告警/调度/数据校验场景、HA 与 Failover 等；L2 仅保留 Java 内存级组件交互（见 3.4）。

**工具链**：`t-layer3-test`（已具备完整能力）：


1. 构建并部署完整 TapData 环境（Java 1.8 + Maven，产物约 1-2 小时）
./run build
./run start tapdata          # 启动后访问 http://127.0.0.1:13030 验证

2. 部署测试数据库（MacBook Apple 芯片环境，./run install 初始化依赖）
./run start db mysql
./run reset_db               # 重置所有测试数据库（删除并重建）

3. 运行用例（编号体系：1_basic_dummy / 2_basic / 3_mysql / 4_mongo / 6_oracle / g19_db2 ...）
./run 1                      # 运行编号 1 相关用例
./taptest run -t=1.1 -f=config.yaml
./taptest run -h             # 查看运行参数
./taptest ls -c -f           # 查看功能用例数量

**用例写法（真实风格，取自 `auto_test/items/g2_basic/item_2_1.py`）**：


from auto_test.init.env import *
from auto_test.utils import *

title = "mock to mdb 20 fields full/realtime"
desc = "Using a mock to mdb task to do benchmark test, please care about the QPS."
case_type = "f"          # f=功能 / p=性能 / s=冒烟

def item():
    task_name = S("mock_to_mdb_20fields")      # 唯一命名（自动加时间戳后缀）
    p = Pipeline(task_name, mode="sync")
    source = S("qa_mock_100w")
    sink = S("qa_mongodb_repl_42240")
    p.readFrom(source + ".mock_100w").writeTo(sink + ".sink_100w", pk=["id"])
    # 启动任务并生成报告：t=超时秒数, l1=全量QPS下限, l2=增量QPS下限
    return simple_report(p, 1800, 0, 0)

if name == "main":
    item()

**覆盖场景（对应产品稳定性承诺）**：HA 主备切换、节点 Failover 后任务自动恢复、断点续传（Checkpoint Resume）、长时间运行内存稳定、高并发写入不丢失/不重复、性能基准（`benchmark/` 与 `auto_test/benchmarksql`）。

3.6 缺口清单与补齐优先级

#
缺口
所在仓库/模块
建议层级
优先级
1
tapdata-pdk-api 契约测试仅 1 个
plugin-kit
L0
P0
2
连接器公共库 CDC 核心逻辑（mysql-core/postgres-core 等）测试不足
tapdata-connectors
L1
P0
3
部分高危 CDC 连接器 0 测试（DB2/Oracle/Sybase 等企业版）
connectors-enterprise
L1
P0
4
iengine 节点级（processor/data 节点）逻辑测试不足
tapdata/iengine
L0
P1
5
manager/tm-api、tm-common 公共逻辑测试不足
tapdata/manager
L0
P1
6
L2 Java 组件级集成用例缺失（Spring Test 基建未建立）
tapdata/manager、tapdata/iengine
L2
P1
7
L0 覆盖率无统一度量（Sonar 已配置 exclusions，未形成门禁）
全部 Java 模块
门禁
P1
8
测试报告未自动化汇总（t-layer3-test 结果分散在 log）
t-layer3-test
门禁
P2


---

四、阶段 3：集成 CI/CD 流程

4.1 现有 CI 现状盘点

仓库
现有流水线
覆盖范围
tapdata-enterprise
.github/workflows/build.yml、cloud.yml
构建、发布
tapdata-agent
Jenkinsfile
Agent 构建发布
t-layer3-test
nightly.sh（launchd/cron 触发）
全量黑盒回归（basic_cases + connector_cases 编号编排）
Java 模块（tapdata / plugin-kit / connectors）
无统一 PR 门禁
缺失

4.2 目标流水线：提交即测 + PR 门禁 + 每日全量

提交代码（feature 分支）                    提交 PR                                 每日定时（Daily）
+------------------------------+      +------------------------------+      +------------------------------+
| 1. L0：受影响模块 mvn test     | -->  | 1. L0/L1：受影响模块 mvn verify | -->  | 1. 全量 L0/L1：mvn verify      |
| 2. L1：受影响连接器 *IT（本地  |      | 2. L2：受影响模块 Spring Test   |      |    （全部模块）                |
|    或轻量 CI，mvn verify）     |      | 3. 静态检查 / 覆盖率增量检查     |      | 2. L3：t-layer3-test 全量回归   |
+------------------------------+      | 4. 测试报告上传并门禁判断        |      |    （nightly.sh basic+connector）|
                                       +------------------------------+      | 3. benchmark 性能基线对比       |
                                                                           | 4. 报告汇总 + 失败告警 + On-call |
                                                                           +------------------------------+

原则：新代码提交后第一时间执行自动化测试，越早发现问题越早反馈越早修复——L0/L1 在提交代码时的 feature 分支上执行，L2 在提交 PR 时执行，L3 每天定时执行（不再依赖发版前的 Code Freeze 节点）。

**PR 门禁工作流示例（GitHub Actions，按真实仓库路径）**：


.github/workflows/pr-gate.yml（建议放置于 tapdata / tapdata-connectors 仓库根目录）
name: TapData PR Quality Gate

on:
  pull_request:
    branches: ["master", "main", "release/*"]

jobs:
  l0-unit-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: "17"
          distribution: "temurin"
          cache: "maven"
      - name: L0 Unit Tests (iengine / manager / plugin-kit)
        run: |
          mvn -q test -pl iengine/iengine-common,iengine/iengine-app -am -Dtest='*Test' -DfailIfNoTests=false
          mvn -q test -pl manager/tm -am -Dtest='*Test' -DfailIfNoTests=false

  l1-l2-verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: "17"
          distribution: "temurin"
          cache: "maven"
      - name: L1/L2 Integration Tests (Testcontainers + Spring Test)
        run: |
          mvn -q verify -pl iengine/iengine-common,iengine/iengine-app,manager/tm -am
          mvn -q verify -pl connectors/mysql-connector,connectors/mongodb-connector -am

**每日全量回归（复用现有 `t-layer3-test/nightly.sh`，Daily 定时触发）**：


现有 nightly.sh 已支持用例编号编排与参数化，建议将其接入 CI 定时任务（每天定时，而非仅发版前）：
nightly.sh --release=<版本>         全量回归（basic_cases + connector_cases）
nightly.sh -l=<编号列表>            轻量模式（指定用例）
失败时通过告警服务（2.3.3）通知 On-call 值班人员；CDC 链路伪失败按 4.4 Flaky 治理流程归因修复

### 4.3 测试报告输出

| 层级 | 报告产出 | 聚合方式 |
| --- | --- | --- |
| L0 | Surefire XML/HTML 报告（`target/surefire-reports`） | Allure / SonarQube 聚合 |
| L1 | Failsafe 报告（`*IT`，Testcontainers） | CI artifact 归档 |
| L2 | Failsafe 报告（Spring Test `*IT`） | CI artifact 归档 |
| L3 | t-layer3-test 日志（`logger.log_file_path`、`auto_test/log`）+ `item_log` | Daily 报告页（通过率、QPS、延迟、数据校验结果） |
| 门禁 | 覆盖率（Sonar 增量）、通过率阈值 | 流水线状态 + 邮件/飞书告警 |

### 4.4 质量门禁与 Flaky 治理
1. **PR 门禁**：L0/L1 必须 100% 通过；增量代码行覆盖率 ≥ 80%（`sonar.coverage.exclusions` 已在 `tapdata/pom.xml` 配置排除清单，可直接复用）；SonarQube 零 Block/Critical 缺陷。
2. **Daily 门禁**：每日全量回归失败率 > 5% 触发告警并指派 On-call；性能基线（QPS/延迟）下降超过阈值触发 benchmark 对比告警。
3. **Flaky 零容忍**：非确定性失败测试必须强制隔离（`@Disabled` + 工单跟踪，或移动至独立 `flaky` profile），限期修复；禁止通过重试掩盖问题。
4. **测试数据治理**：所有自动化用例使用唯一命名（参考 t-layer3-test `S()`/`testcase_tn()` 机制），用例结束清理，杜绝数据污染。


---

五、团队分工与迭代节奏

5.1 2 周 Sprint 节奏下的测试流转

  Day 1 - 2               Day 3 - 7                Day 8 - 9            Day 10
+-----------+          +------------------+      +-------------+     +------------+
| 需求评审  | -------> | 开发与测试（全流程）| --> | 发布评估     | --> |  发布      |
| 制定 AC   |          | 提交代码：L0/L1    |      | 每日 L3 回归  |     | 验证与上线 |
+-----------+          | 提交 PR：L2 门禁   |      | 回归修复与复跑 |     +------------+
      |                | 每天：L3 全量回归  |      +-------------+
      |                +------------------+
   (Dev+QA)

- **Day 1-2（需求与技术评审）**：开发与测试共同确定可测试性方案及 Accept Criteria（AC），更新 AI 知识库中的服务能力与接口契约（对应阶段 1 持续维护）。
- **Day 3-7（迭代开发阶段）**：开发人员利用 AI 工具跟随业务代码同步生成 L0/L1 测试；**提交代码时在 feature 分支执行 L0/L1**（本地 `mvn test`/`mvn verify`，或轻量 CI），**提交 PR 时由 CI 执行 L2（Spring Test）门禁**；**L3 每天定时执行**，任何一天的回归失败当天归因修复，避免问题积压到发版前夜。
- **Day 8-9（发布评估阶段）**：迭代进入发布评估，以**最近一轮 Daily L3 回归**结果作为发布依据，SDET 根据报告进行边缘场景补充验证；回归问题当日修复并复跑。
- **Day 10（发布阶段）**：最近一轮 Daily 全绿（含冒烟用例 `case_type: s`）后构建 Docker 镜像并打 Tag 发布。

5.2 RACI 矩阵

活动 / 产出物
架构师 (Arch)
开发工程师 (Dev)
测试工程师 (SDET/QA)
DevOps
| AI 协同知识库维护（架构契约/服务能力/规范） | **Accountable** | Responsible | Consulted | Informed |
| L0 单元测试（iengine / manager / plugin-kit / connectors） | Informed | **Accountable/Responsible** | Consulted | Informed |
| L1 集成测试（Testcontainers `*IT`） | Informed | **Accountable/Responsible**（对应模块） | Consulted | Informed |
| L2/L3 用例与框架（Spring Test / t-layer3-test） | Informed | Consulted | **Accountable/Responsible** | Informed |
| CI/CD 门禁流水线（PR Gate / Daily / 报告） | Consulted | Informed | Responsible | **Accountable** |
| Flaky 测试治理与修复 | Informed | **Responsible**（对应模块） | **Accountable**（监控/隔离） | Informed |

**实际责任人映射**：连接器开发者 → 连接器 L0/L1（`*IT`，Testcontainers）；引擎开发者 → iengine L0/L1；控制面开发者 → manager/tm service L0 与 L2（Spring Test）；SDET → L2/L3 用例与 Daily 编排；架构师 → 知识库校正与 L2/L3 场景设计咨询。


---

六、落地路线图

6.1 第 1-2 周：知识库初稿与测试规范

1. 按 2.1 建设流程启动：**定义知识库结构 → AI 工具基于模块代码批量生成初稿（1-2 周内完成首轮）→ 研发按模块人工校正**，沉淀 2.2/2.3 的组件与契约内容，校正准确后版本化作为 AI Context 基线；
2. 并行发布 2.4 单元/集成测试规范与测试用例模板（作为 Code Review 检查项），**不等待知识库全部完成**；
3. 依据 3.1 盘点结果建立测试资产基线台账，确认 3.6 缺口清单与责任人；
4. 知识库校正到位后，基于测试模板 + 知识库文档 + 模块代码，利用 AI 生成 L0/L1 测试用例，开发人员人工校准后入库（随迭代增量推进，不要求一次完成）。
6.2 第 1-2 月：补齐 L0/L1，建立 PR 门禁

1. P0 缺口：tapdata-pdk-api 契约测试、连接器公共库 CDC 核心逻辑与高危连接器 *IT 补齐（Testcontainers 标准方式）；
2. iengine 节点级与 tm-api/tm-common 逻辑测试（P1）；
3. 建立 PR Gate 工作流（4.2 示例：L0/L1 提交时执行 + L2 PR 时执行）与 Surefire/Failsafe/Allure 报告聚合；
4. 建立 Daily 全量回归（t-layer3-test，每天定时）并输出报告。
6.3 第 3 月+：L2/L3 与门禁固化

1. L2 Java 组件级集成场景矩阵扩展（告警、调度、数据校验、类型映射、引擎节点交互）；
2. L3/E2E 稳定性场景扩充（HA/Failover/断点续传/长时间运行、跨库同步链路）与性能基线对比；
3. 覆盖率与通过率门禁固化，Flaky 治理流程（4.4）常态化；
4. 测试报告自动化汇总与告警闭环，形成"提交即测 + PR 门禁 + 每日全量"的稳定节奏。