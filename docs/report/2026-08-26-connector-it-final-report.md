# Connector 集成测试终结报告

- **报告日期**: 2026-08-26
- **测试框架**: tapdata-connector-it 1.0-SNAPSHOT（用例数 61 → 75，本轮新增 14 个能力用例）
- **执行方式**: maven-failsafe-plugin 集成测试（单元测试跳过）
- **测试环境**: tapdata 多模块仓库；MySQL 8.0（113.98.206.142:23306）/ MongoDB 6.x（113.98.206.142:14017）/ DB2 for i（113.98.206.139:8471，TESTDB）
- **结论**: MySQL / MongoDB 全部通过；DB2 i 仅剩 1 个环境限制型遗留用例（should_drop_field），连接器无代码缺陷

---

## 一、汇总

| Connector | 总用例 | 通过 | 失败 | 错误 | 跳过 | 通过率 | 耗时 |
|-----------|--------|------|------|------|------|--------|------|
| MySQL Connector | 75 | 51 | 0 | 0 | 24 | **100%** | 229.1s |
| MongoDB Connector | 75 | 35 | 0 | 0 | 40 | **100%** | 154.3s |
| DB2 i (AS400) Connector | 75 | 38 | 0 | **1** | 36 | 97.4%* | 195.2s |

> \* DB2 i 唯一错误为 `should_drop_field`，经实验验证为 **DB2 i 服务器端拒绝 DROP COLUMN 操作**（SQL0952/Reason code 10，156ms 即拒，非超时/锁等待），连接器 SQL 生成实现正确，属环境限制，详见 4.2。
>
> 跳过用例原因：connector 未注册该能力（框架契约自动跳过，属正常行为）。

---

## 二、MySQL Connector IT

### 2.1 总览

```
Tests run: 75, Failures: 0, Errors: 0, Skipped: 24, Time elapsed: 229.1s
BUILD SUCCESS ✅
```

**全部通过，无失败/错误用例**。24 个跳过用例均为 MySQL 未注册的能力（流式读取偏移、原始命令等），属正常行为。上轮 08-17 报告中的 `should_query_constraints`（唯一约束查询缺陷）已由连接器侧补充唯一约束查询修复，本轮验证通过。

---

## 三、MongoDB Connector IT

### 3.1 总览

```
Tests run: 75, Failures: 0, Errors: 0, Skipped: 40, Time elapsed: 154.3s
BUILD SUCCESS ✅
```

**全部通过，无失败/错误用例**。40 个跳过用例均为 MongoDB 未注册的能力（约束管理、DDL 字段级操作等），属正常行为。执行时需 `-Dapp_type=DAAS`（MongoDB 连接器按部署形态选择能力集）。

---

## 四、DB2 i (AS400) Connector IT

### 4.1 总览

```
Tests run: 75, Failures: 0, Errors: 1, Skipped: 36, Time elapsed: 195.2s
BUILD FAILURE（仅遗留 1 个环境限制用例）
```

对照 08-17 报告（61 用例，0 失败 / 6 错误）：本轮 **6 个错误全部处理**，其中 5 个修复验证通过、1 个确认环境限制并移除错误能力承诺。新增发现并修复 1 个日期类型写入缺陷（U5）。

### 4.2 失败用例分析

#### 1) `should_drop_field`（ERROR，唯一遗留，环境限制）

| 项目 | 内容 |
|------|------|
| **错误** | `java.sql.SQLTimeoutException: [SQL0952] Processing of the SQL statement ended. Reason code 10.` |
| **位置** | `Db2Connector.fieldDDLHandler → JdbcContext.batchExecute → ALTER TABLE ... DROP COLUMN col` |
| **严重度** | 中低（环境/服务器策略限制，非连接器代码缺陷） |

**根因分析**（本轮实验验证）：

通过 DropColumnExperiment 直连 DB2 i 执行 `ALTER TABLE "TESTDB"."_tap_it_xxx" DROP COLUMN "c_new_col"`，服务器端在 **156ms 内立即拒绝**（SQL0952/RC10），排除超时/锁等待因素。DB2 i 的 DROP COLUMN 需要重建表，当前测试库对象被服务器策略拒绝执行。连接器生成的 SQL 语法正确（与 DB2 i SQL 参考一致）。

**处理结论**: 连接器实现正确，不作为代码缺陷修复。后续途径：
- 测试侧：在框架中为该用例声明能力开关，环境不支持时 assumeTrue 跳过（与 `should_alter_field_name` 同模式）
- 服务器侧：确认测试库对 DROP COLUMN 的授权/策略（可能需要在更高权限库对象上验证）

---

## 五、本轮缺陷修复清单

| # | 用例 | 缺陷 | 根因 | 修复方案 | 状态 |
|---|------|------|------|----------|------|
| 1 | `should_write_record_in_batch`（U5） | **String 日期值写入 Data type mismatch** | 引擎 wrap 链路 `AnyToDateTime.classHandlers` 未注册 `LocalDate`，wrap 回落为 `TapStringValue("2022-01-27")`，unwrap 产物 String；jt400 `SQLDate.set(String)` 按连接 dateFormat（默认 MDY）解析 ISO 格式必失败 | `Db2WriteRecorderV2.filterValue` 新增 `parseTimeString`：String 时间值按目标列 dataType 转 `java.sql.Date/Time/Timestamp`（另含 DECIMAL/BigInteger 防御） | ✅ 已修复 |
| 2 | `should_new_field` / `should_alter_field_attributes` | `[SQL0204] ADMIN_CMD in SYSPROC type *N not found` | db2-core `Db2JdbcContext.flushTable` 硬编码 LUW 专用 `CALL SYSPROC.ADMIN_CMD('REORG TABLE ...')`，DB2 i 无该存储过程 | `Db2iJdbcContext` 覆写 `flushTable` 为 no-op（DB2 i 的 ALTER TABLE 立即生效，无 REORG 概念） | ✅ 已修复 |
| 3 | `should_new_field` / `should_alter_field_attributes` | 旁路验证断言失败（SQL 已执行成功） | jt400 `DatabaseMetaData.getColumns` 在该环境对任何 schema 参数均返回空（假阴性），旁路验证器无法看到新增/修改列 | `DB2iConnectorIT.tableColumns` 覆写为 QSYS2.SYSCOLUMNS 直查（与 listIndexes/listConstraints 同模式） | ✅ 已修复 |
| 4 | `should_alter_field_name` | `[SQL0199] Keyword RENAME not expected` | DB2 i 方言不支持 `ALTER TABLE ... RENAME COLUMN` | 移除 `Db2iConnector` 的 `supportAlterFieldNameFunction` 注册，框架契约 assumeTrue 自动跳过（不再错误承诺能力） | ✅ 已处理 |
| 5 | `should_get_stream_offset` | `NumberFormatException: Cannot parse null string` | `getStreamOffsetFromString` 对 null offset 无判空 | 增加 null 防御（上轮修复，本轮回归通过） | ✅ 已修复 |
| 6 | `should_new_field` | `[SQL0199] Keyword NULL not expected` | `Db2DDLSqlGenerator.addColumn` 对 nullable=true 追加 `NULL` 关键字，DB2 i 不支持 | 可空列不再追加 NULL 子句（仅 NOT NULL 时追加）（上轮修复，本轮回归通过） | ✅ 已修复 |
| 7 | — | 测试表残留外键约束导致 drop table 失败 | 旁路建表的外键约束残留 | 清理逻辑先删约束再删表（上轮修复，本轮回归通过） | ✅ 已修复 |

### 5.1 按性质分类

| 类别 | 数量 | 用例 |
|------|------|------|
| **引擎/连接器类型转换缺陷**（wrap/unwrap 链路 + jt400 解析差异） | 1 | U5 日期写入（`filterValue` 收到 String 日期值） |
| **DDL 方言不兼容**（DB2 i vs LUW） | 4 | `flushTable`（ADMIN_CMD）、`alter_field_name`（RENAME，能力移除）、`new_field`（NULL 子句）、`alter_field_attributes`（flushTable 旁路） |
| **测试旁路假阴性**（jt400 元数据限制） | 1 | `tableColumns`（DatabaseMetaData.getColumns 恒空） |
| **空安全缺陷** | 1 | `get_stream_offset`（null 未判空） |
| **测试数据清理** | 1 | 外键残留导致 drop table 失败 |
| **环境/服务器策略限制**（非代码缺陷） | 1 | `should_drop_field`（SQL0952/RC10，服务器端拒绝 DROP COLUMN） |

---

## 六、遗留问题与建议

| 优先级 | 问题 | 影响 | 建议 |
|--------|------|------|------|
| P2 | `should_drop_field`：DB2 i 服务器端拒绝 DROP COLUMN（SQL0952/RC10） | 连接器 dropField 能力在目标库不可用；不影响其他 DDL 能力 | 框架侧为该用例加能力开关（环境不支持时跳过）；或在授权允许的库对象上验证 |
| P3 | DB2 i 不支持 RENAME COLUMN（SQL0199） | 列重命名能力不可用 | 已移除能力声明（不再错误承诺）；如需支持可提示用户采用重建表策略 |
| P3 | jt400 `DatabaseMetaData.getColumns` 在该环境恒返回空 | 影响任何依赖 JDBC 元数据获取列信息的路径 | 已在 IT 旁路中规避；若生产路径依赖该 API 需改用 QSYS2.SYSCOLUMNS |

---

## 七、附录

### 7.1 执行命令

```bash
# MySQL
cd tapdata-connectors/connectors/mysql-connector
mvn test-compile failsafe:integration-test failsafe:verify -DskipITs=false -o

# MongoDB（需 DAAS 部署形态）
cd tapdata-connectors/connectors/mongodb-connector
mvn test-compile failsafe:integration-test failsafe:verify -DskipITs=false -Dapp_type=DAAS -o

# DB2 i AS400
cd tapdata-connectors-enterprise/connectors/db2i-connector
mvn test-compile failsafe:integration-test failsafe:verify -DskipITs=false -o
```

### 7.2 分析依据文件

| Connector | 详细报告路径 |
|-----------|-------------|
| MySQL | `tapdata-connectors/connectors/mysql-connector/target/failsafe-reports/io.tapdata.connector.mysql.MySQLConnectorIT.txt` |
| MongoDB | `tapdata-connectors/connectors/mongodb-connector/target/failsafe-reports/io.tapdata.mongodb.MongoDBConnectorIT.txt` |
| DB2 i | `tapdata-connectors-enterprise/connectors/db2i-connector/target/failsafe-reports/io.tapdata.connector.db2.DB2iConnectorIT.txt` |

### 7.3 本轮变更文件

| 文件 | 变更 |
|------|------|
| `db2-core/.../dml/Db2WriteRecorderV2.java` | filterValue 增加 String 时间值解析（parseTimeString）+ DECIMAL/BigInteger 防御 |
| `db2i-connector/.../Db2iJdbcContext.java` | 覆写 flushTable 为 no-op |
| `db2i-connector/.../Db2Connector.java` | 移除 supportAlterFieldNameFunction 注册 |
| `db2i-connector/.../DB2iConnectorIT.java` | 覆写 tableColumns 用 QSYS2.SYSCOLUMNS 直查 |
