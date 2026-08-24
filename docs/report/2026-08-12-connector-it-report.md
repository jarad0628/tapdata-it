# TapData Connector 集成测试汇总报告

**执行时间**: 2026-08-12 ~13:37  
**执行环境**: macOS 26.5.2, JDK 21.0.1  
**测试框架**: TapData IT (ConnectorIT 基类)，每连接器 55 个通用测试用例

---

## 一、整体概览

| 连接器 | 总计 | 通过 | 失败 | 错误 | 跳过 | 耗时 | 结果 |
|--------|:----:|:----:|:----:|:----:|:----:|:----:|:----:|
| **MySQL Connector** | 55 | 30 | 4 | 1 | 20 | 23s | ⚠ 部分失败 |
| **MongoDB Connector** | 55 | 22 | 0 | 0 | 33 | 12s | ✅ 全部通过 |
| **DB2 i Connector** | 55 | 24 | 0 | 5 | 26 | 142s | ⚠ 部分失败 |
| **合计** | **165** | **76** | **4** | **6** | **79** | **177s** | — |

> **跳过说明**: 跳过测试均为未注册的 ConnectorFunctions 能力（如 constraints、streamRead、batchRead 等），属正常行为。

---

## 二、MySQL Connector — 详细分析

**用例**: `tapdata-connectors/connectors/mysql-connector/src/it/java/io/tapdata/connector/mysql/MySQLConnectorIT.java`  
**连接**: `127.0.0.1:13307 / test`

### 2.1 失败/错误详情 (5 个)

| # | 测试用例 | 类型 | 根因 | 影响 |
|---|---------|:----:|------|:----:|
| 1 | `should_create_and_query_constraint` | **FAILURE** | 创建的唯一约束 `uq_c_int` 在 `queryConstraints` 返回中不可见 | 约束查询契约不一致 |
| 2 | `should_stream_read_incremental` | **FAILURE** | DATETIME 字段期望 `2020-01-25T21:46:08.892`，实际得时间戳 `1579988769000`（毫秒值） | CDC 流读的 datetime 字段类型映射或序列化异常 |
| 3 | `should_stream_read_multi_connection` | **FAILURE** | 15s 内未从流式读取通道收到任何记录（`got: 0`） | 可能是 CDC 部署/配置问题或超时不足 |
| 4 | `should_write_update_records` | **FAILURE** | TIMESTAMP 字段时区偏移 8 小时：期望 `10:45:45Z`，实际 `18:45:45Z` | TIMESTAMP 存储/读取时的时区未归一化 |
| 5 | `should_drop_constraint` | **ERROR** | SQL 语法错误：`DROP CONSTRAINT \`uq_c_int\`` — MySQL 不支持 `DROP CONSTRAINT`，需用 `DROP INDEX` | 通用 DDL 未兼容 MySQL |

### 2.2 通过 / 跳过

- **通过 30 项**: 连接测试、表 DDL（createTableV2）、数据写入/删除/批量读取（batchReadAll）、事务提交与回滚、schema 发现、getTableInfo、字段类型映射、原始命令执行等均正常。
- **跳过 20 项**: getReadPartitions、queryByFilter、streamRead 的部分变体等未注册能力。

---

## 三、MongoDB Connector — 详细分析

**用例**: `tapdata-connectors/connectors/mongodb-connector/src/it/java/io/tapdata/mongodb/MongoDBConnectorIT.java`  
**连接**: `127.0.0.1:27017 / test`

### 3.1 测试结果

- **全部通过**: 0 失败, 0 错误
- **通过 22 项**: createTableV2（幂等建集合）、schema 发现（含隐式 _id 字段处理）、写入/更新/删除、批量读取、流式读取、事务操作、executeCommand 等核心能力均稳定。
- **跳过 33 项**: clearTable、constraints（MongoDB 无 SQL 约束概念）、rawQueryCommand 等不适用能力。
- **非致命警告**: 存在 `TapMapping` Infinity 数值映射警告（Decimal128 的 "-Infinity"/"Infinity" 范围值），不影响测试结果。

### 3.2 说明

MongoDB 特性开关（`ConnectorTestContext`）正确适配了 NoSQL 语义：
- `createTableReportsTableExists=false`
- `schemaDiscoveryRequiresSampleData=true`
- `schemaAllowsExtraFields=true`（_id 隐式字段）
- `schemaPrimaryKeyStrict=false`
- `executeCommandSupportsPing=false`
- `fieldMinMaxRequiresPartitionIndex=true`

---

## 四、DB2 i Connector — 详细分析

**用例**: `tapdata-connectors-enterprise/connectors/db2i-connector/src/it/java/io/tapdata/connector/db2/DB2iConnectorIT.java`  
**连接**: `113.98.206.139:8471 / TESTDB`

### 4.1 错误详情 (5 个)

| # | 测试用例 | 类型 | 根因 | 错误代码 |
|---|---------|:----:|------|:--------:|
| 1 | `should_new_field` | **ERROR** | `ALTER TABLE ADD COLUMN ... NULL` 语法错误 — DB2 i 在添加列时不允许 `NULL` 关键字 | SQL0199 |
| 2 | `should_drop_field` | **ERROR** | `ALTER TABLE DROP COLUMN` 超时（Reason code 10），可能因表上有依赖或锁 | SQL0952 |
| 3 | `should_alter_field_name` | **ERROR** | `ALTER TABLE RENAME COLUMN` 语法错误 — DB2 i 不支持此语法 | SQL0199 |
| 4 | `should_alter_field_attributes` | **ERROR** | 调用 `SYSPROC.ADMIN_CMD` 刷新表统计信息失败 — 该存储过程不存在或权限不足 | SQL0204 |
| 5 | `should_flush_offset_and_get_stream_offset` | **ERROR** | `Long.parseLong(null)` — `Db2Connector.getStreamOffsetFromString` 接收了 null 字符串 | — |

### 4.2 分析

前 4 个错误均集中在 **字段 DDL 操作**（`Db2Connector.fieldDDLHandler`），根因分为两类：

1. **DB2 i SQL 方言不兼容**（#1, #3）：
   - DB2 i 的 `ALTER TABLE ADD COLUMN` 不能带 `NULL` 关键字
   - DB2 i 不支持 `ALTER TABLE RENAME COLUMN`（需用 `RENAME COLUMN` 独立语句）
   - 通用 DDL 生成未针对 AS400 做方言适配

2. **运行时环境/权限问题**（#2, #4）：
   - `DROP COLUMN` 超时（Reason code 10 = 取消/超时）
   - `ADMIN_CMD` 存储过程缺失或权限不足

3. **空指针防御缺失**（#5）：
   - `getStreamOffsetFromString` 直接调用 `Long.parseLong(str)` 未做 null 检查

4. **通过 24 项**: 连接测试、建表、数据写入/读取、count、事务提交回滚、类型映射、schema 发现、executeCommand 等正常。
5. **跳过 26 项**: streamRead、batchRead、constraints 等未注册能力。

### 4.3 耗时突出

DB2 i 测试耗时 **142s**（MySQL 仅 23s），主要因远程连接延迟（上海 → 远程 AS400 服务器）和 DDL 超时重试所致。

---

## 五、逐用例通过矩阵

| 测试用例 | MySQL | MongoDB | DB2 i |
|---------|:-----:|:-------:|:-----:|
| should_test_connection | ✅ | ✅ | ✅ |
| should_create_table_v2 | ✅ | ✅ | ✅ |
| should_create_table_v2_when_exists | ❌ skip | ✅ skip | ✅ skip |
| should_discover_schema_of_created_table | ✅ | ✅ | ✅ |
| should_write_insert_records | ✅ | ✅ | ✅ |
| should_write_update_records | ❌ | ✅ | ✅ |
| should_write_delete_records | ✅ | ✅ | ✅ |
| should_transaction_commit | ✅ | ✅ | ✅ |
| should_transaction_rollback | ✅ | ✅ | ✅ |
| should_batch_read_data_consistent | ✅ | ✅ | ❌ skip |
| should_stream_read_incremental | ❌ | ✅ | ❌ skip |
| should_stream_read_multi_connection | ❌ | ✅ | ❌ skip |
| should_new_field | ✅ | ✅ | ❌ |
| should_drop_field | ✅ | ❌ skip | ❌ |
| should_alter_field_name | ✅ | ❌ skip | ❌ |
| should_alter_field_attributes | ✅ | ❌ skip | ❌ |
| should_flush_offset_and_get_stream_offset | ✅ | ✅ | ❌ |
| should_create_and_query_constraint | ❌ | ❌ skip | ❌ skip |
| should_drop_constraint | ❌ | ❌ skip | ❌ skip |
| should_execute_command | ✅ | ✅ | ✅ |
| should_get_table_info | ✅ | ✅ | ✅ |
| should_query_by_filter | ❌ skip | ❌ skip | ❌ skip |
| should_get_read_partitions | ❌ skip | ❌ skip | ❌ skip |
| should_export_event_sql | ✅ | ❌ skip | ✅ |

---

## 六、汇总建议

| 优先级 | 问题 | 涉及 | 建议修复方向 |
|:------:|------|:----:|-------------|
| P0 | **DB2 i fieldDDLHandler 方言不兼容** | DB2 i | 为 DB2 i 的 `ALTER TABLE` 定制 SQL 模板：移除 NULL 关键字、RENAME COLUMN 改用独立语句 |
| P0 | **DB2 i getStreamOffsetFromString 空指针** | DB2 i | 增加 null/空串防御，返回默认偏移或抛出明确异常 |
| P1 | **MySQL DROP CONSTRAINT 语法** | MySQL | `CommonDbConnector.dropConstraint` 增加 MySQL 分支（`DROP INDEX`） |
| P1 | **MySQL TIMESTAMP 时区偏移** | MySQL | TIMESTAMP 读取/写入的时区归一化（UTC 对齐） |
| P2 | **MySQL streamRead 超时** | MySQL | CDC 连接器启动验证或增加超时容忍度 |
| P2 | **DB2 i ADMIN_CMD 缺失** | DB2 i | 检测环境是否支持 `SYSPROC.ADMIN_CMD`，不支持时降级或跳过字段属性变更测试 |

---

> **注 1**: MySQL IT 运行中 surefire 单元测试（15/17 错误）因 Java 21 + Byte Buddy 不兼容而失败，不影响集成测试结果。已使用 `failsafe:integration-test` 直接绕过此项。  
> **注 2**: MongoDB IT 运行中也存在 Byte Buddy 兼容性问题，同样使用 `failsafe:integration-test` 绕过。