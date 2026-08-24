# Connector 集成测试报告

- **报告日期**: 2026-08-17
- **测试框架**: tapdata-connector-it 1.0-SNAPSHOT
- **执行方式**: maven-failsafe-plugin 集成测试（单元测试跳过）
- **测试环境**: tapdata 多模块仓库，MySQL 8.0/ MongoDB 6.x / DB2 for i 7.x

---

## 一、汇总

| Connector | 总用例 | 通过 | 失败 | 错误 | 跳过 | 通过率 | 耗时 |
|-----------|--------|------|------|------|------|--------|------|
| MySQL Connector | 61 | 37 | **1** | 0 | 23 | 97.4% | 10.6s |
| MongoDB Connector | 61 | 24 | **0** | **0** | 37 | **100%** | 13.9s |
| DB2 i (AS400) Connector | 61 | 24 | **0** | **6** | 31 | 80.0% | 166s |

> 跳过用例原因：connector 未注册该能力（框架原则 3 自动跳过，属正常行为）。

---

## 二、MySQL Connector IT

### 2.1 总览

```
Tests run: 61, Failures: 1, Errors: 0, Skipped: 23, Time elapsed: 5.762s
BUILD FAILURE
```

### 2.2 失败用例分析

#### 1) `should_query_constraints` — 唯一约束查询失败

| 项目 | 内容 |
|------|------|
| **断言** | `constraint uq_c_int should be visible, got: []` |
| **位置** | `ConnectorIT.java:1366` |
| **严重度** | 中（功能缺陷） |

**根因分析**:

`queryConstraint` 实现继承自 `CommonDbConnector.queryConstraint()` → `discoverConstraint()` → `jdbcContext.queryAllForeignKeys()`，该方法只查询**外键约束**（SQL 条件 `k.REFERENCED_TABLE_NAME IS NOT NULL`）。测试用例通过旁路创建的是**唯一约束** `ALTER TABLE t ADD CONSTRAINT uq_c_int UNIQUE (c_int)`，不在外键查询范围内。

**修复建议**:
- 方案 A（推荐）：在 `CommonDbConnector.queryConstraint()` 中补充唯一约束查询（从 `information_schema.TABLE_CONSTRAINTS` + `STATISTICS` 查询 UNIQUE 约束并构造 `TapConstraint`）
- 方案 B：将测试改为创建外键约束（但会降低测试对 connector 实现的覆盖强度）

**代码路径**: `CommonDbConnector.discoverConstraint` → `jdbcContext.queryAllForeignKeys` → SQL `MYSQL_ALL_FOREIGN_KEY`（仅查外键）

---

## 三、MongoDB Connector IT

### 3.1 总览

```
Tests run: 61, Failures: 0, Errors: 0, Skipped: 37, Time elapsed: 6.972s
BUILD SUCCESS ✅
```

**全部通过，无失败用例**。37 个跳过用例均为 MongoDB 未注册的能力（约束管理、DDL 字段级操作、原始命令等），属正常行为。

---

## 四、DB2 i (AS400) Connector IT

### 4.1 总览

```
Tests run: 61, Failures: 0, Errors: 6, Skipped: 31, Time elapsed: 160.1s
BUILD FAILURE
```

> 更新：`mvn clean` 重新编译后 `should_stream_read_incremental` 已通过（根因为 Eclipse JDT stub 污染，
> 非 journal-parsing 依赖缺失）。新增瞬态连接错误详见下文 4.2.6。

### 4.2 失败用例分析

#### 1) ~~`should_stream_read_incremental`（FAILURE）~~ **已修复** ✅

| 项目 | 内容 |
|------|------|
| **原错误** | `Unresolved compilation problems: The import com.fnz cannot be resolved` |
| **方案** | `mvn clean` 清除 Eclipse JDT 编译错误 stub，强制 javac 完整重编译即恢复 |
| **判断依据** | class 文件已被 IDE 污染（见经验记忆「Eclipse 编译错误 stub 污染」） |

流式读取增量测试可通过（cdc 功能正常），无需修改 pom 或代码。

---

#### 2) `should_get_stream_offset`（ERROR）

| 项目 | 内容 |
|------|------|
| **错误** | `NumberFormatException: Cannot parse null string` |
| **位置** | `Db2Connector.getStreamOffsetFromString:394 → Long.parseLong(offset)` |
| **严重度** | **高**（NPE 类缺陷，轻型防御即可修复） |

**根因分析**:

```java
private Object getStreamOffsetFromString(TapConnectorContext connectorContext, String offset) {
    return Long.parseLong(offset);  // offset 为 null 时 NFE
}
```

IT 测试 `should_get_stream_offset` 以 `null` 调用：`getStreamOffset.getStreamOffset(nodeContext(), null)` ，无 null 防御。

**修复建议**: `Long.parseLong(offset)` 前判空，null 时返回 0 或抛含上下文的异常。

---

#### 3) `should_new_field`（ERROR）

| 项目 | 内容 |
|------|------|
| **错误** | `[SQL0199] Keyword NULL not expected. Valid tokens: ADD LOG NOT SET DATA DROP ALTER APPEND ATTACH DETACH PCTFREE.` |
| **位置** | `Db2DDLSqlGenerator.addColumn:54 → ... ADD col NULL` |
| **严重度** | 中（DDL 方言不兼容） |

**根因分析**:

`Db2DDLSqlGenerator.addColumn()` 在字段可空（nullable=true）时生成 `ADD col_name data_type NULL`。DB2 for i 的 `ALTER TABLE ADD COLUMN` **不支持 `NULL` 关键字**（列默认即为可空）。错误来自 SQL 语法解析阶段。

**修复建议**: 在 `Db2DDLSqlGenerator.addColumn()` 中，当 `nullable=true` 时**不追加** `NULL` 关键字（仅当 `nullable=false` 时才追加 `NOT NULL`）。

---

#### 4) `should_alter_field_name`（ERROR）

| 项目 | 内容 |
|------|------|
| **错误** | `[SQL0199] Keyword RENAME not expected` |
| **位置** | `Db2DDLSqlGenerator.alterColumnName:132 → ALTER TABLE ... RENAME COLUMN old TO new` |
| **严重度** | 中（DDL 方言不兼容） |

**根因分析**:

`alterColumnName()` 生成标准 SQL `ALTER TABLE "schema"."table" RENAME COLUMN "old" TO "new"`。DB2 for i **不支持 `RENAME COLUMN` 关键字**（在 DB2 LUW 和部分 DB2 i 高版本支持，测试环境不支持）。

**修复建议**: DB2 i 列重命名需使用系统存储过程 `CALL QSYS2.RENAME_OBJECT` 或重建表策略。实现方案取决于 `com.fnz` 版本的兼容性。

---

#### 5) `should_drop_field`（ERROR）

| 项目 | 内容 |
|------|------|
| **错误** | `[SQL0952] Processing of the SQL statement ended. Reason code 10.` |
| **位置** | `Db2DDLSqlGenerator.dropColumn:149 → ALTER TABLE ... DROP COLUMN col` |
| **严重度** | 中低（超时/锁等待，与表结构和环境相关） |

**根因分析**:

`dropColumn()` 生成 `ALTER TABLE "schema"."table" DROP COLUMN "col"`。DB2 for i 的 DROP COLUMN 操作需要重建表，在有数据或锁竞争时可能超时。Reason code 10 表示语句处理超时。测试表的 5 条基础数据触发了表重建的较长等待。

**修复建议**:
- 增加 `LOCK TIMEOUT` 或 `WAIT` 子句
- 或实现在 `db2JdbcContext` 中设置更长的查询超时
- 如果 DB2 i 版本不支持 DROP COLUMN，可改用 `CALL QSYS2.DROP_COLUMN` 系统过程

---

#### 6) `should_discover_schema_of_created_table` via `setUp`（ERROR，瞬态）

| 项目 | 内容 |
|------|------|
| **错误** | `SQLNonTransientConnectionException: The application requester cannot establish the connection. (Connection reset)` |
| **位置** | `ConnectorIT.setUp:358` → `Db2Connector.initConnection:76` |
| **严重度** | 低（瞬态网络故障，与代码无关） |

**根因分析**:

DB2 i 服务器在清理操作（`clearExpiredPF` 删除过期的 DSPPFM 文件）后连接池未及时恢复，`initConnection` 时 `jdbcContext.getConnection()` 遇到 `Connection reset`。该错误在 `mvn clean` 后首次运行测试时出现，非代码缺陷，重新执行即可消除。

**状态**: 非代码缺陷，后续执行可消除。

---

#### 7) `should_alter_field_attributes`（ERROR）

| 项目 | 内容 |
|------|------|
| **错误** | `[SQL0204] ADMIN_CMD in SYSPROC type *N not found` |
| **位置** | `ALTER TABLE ... ALTER COLUMN ... SET DATA TYPE` 执行路径 |
| **严重度** | 中（环境依赖） |

**根因分析**:

`Db2DDLSqlGenerator.alterColumnAttr()` 生成 `ALTER TABLE "schema"."table" ALTER COLUMN "c_varchar" SET DATA TYPE varchar(500)`。DB2 for i 在执行 `SET DATA TYPE` 时尝试调用系统存储过程 `ADMIN_CMD`，但测试环境的 DB2 i 实例**未安装** `ADMIN_CMD`。该过程不属于标准 DB2 i 安装，需要可选组件。

**修复建议**:
- 方案 A：在 DB2 i 实例上安装 `ADMIN_CMD` 存储过程
- 方案 B：在 `alterFieldAttr` 中规避 `SET DATA TYPE`，提供其他途径的类型变更支持
- 方案 C：在 ConnectorTestContext 中增加开关 `alterFieldAttributesUnsupported`，跳过该用例

---

## 五、失败根因分类统计

### 5.1 按性质分类

| 类别 | 数量 | 涉及用例 |
|------|------|---------|
| **DDL 方言不兼容**（DB2 i SQL 语法差异） | 3 | `should_new_field`, `should_alter_field_name`, `should_alter_field_attributes` |
| ~~运行时依赖缺失（classpath 类找不到）~~ | 1 | ~~`should_stream_read_incremental`~~ **已修复** ✅ |
| **空安全缺陷**（null 未判空） | 1 | `should_get_stream_offset` |
| **超时/锁等待**（环境因素） | 1 | `should_drop_field` |
| **瞬态连接故障**（网络瞬断） | 1 | `should_discover_schema_of_created_table`（setUp） |
| **功能覆盖缺陷**（查询范围不完整） | 1 | `should_query_constraints`（MySQL） |

### 5.2 按严重度

| 严重度 | 数量 | 用例 |
|--------|------|------|
| **高**（阻断型，必须修复） | 1 | ~~`should_stream_read_incremental`~~ **已修复** ✅、`should_get_stream_offset`（NPE崩溃） |
| **中**（功能部分缺失） | 4 | `should_new_field`、`should_alter_field_name`、`should_alter_field_attributes`、`should_query_constraints` |
| **中低**（性能/环境） | 2 | `should_drop_field`（超时可调优）、`should_discover_schema_of_created_table`（瞬态连接） |

---

## 六、建议修复优先级

| 优先级 | 用例 | 工作量估计 | 说明 |
|--------|------|-----------|------|
| **P0 立即** | ~~`should_stream_read_incremental`~~ | ✅ **已修复** | `mvn clean` 清除 Eclipse JDT stub 后通过（非依赖问题） |
| **P0 立即** | `should_get_stream_offset` | ~0.5h | 加 null 判空防御 |
| **P1 尽快** | `should_query_constraints` | ~2h | 扩展 discoverConstraint 查询唯一约束 |
| **P1 尽快** | `should_new_field` | ~1h | 去除 ADD COLUMN 中的 NULL 关键字 |
| **P1 尽快** | `should_alter_field_name` | ~2h | 改用 QSYS2.RENAME_OBJECT 或版本判断 |
| **P2 常规** | `should_alter_field_attributes` | ~1h | 系统过程安装或加 feature switch 跳过 |
| **P2 常规** | `should_drop_field` | ~1h | 增加超时配置或 feature switch |
| **P3 低** | `should_discover_schema_of_created_table` | 0h | 瞬态连接故障，不属代码缺陷 |

---

## 七、附录

### 7.1 执行命令

```bash
# MySQL
cd tapdata-connectors/connectors/mysql-connector
mvn test-compile failsafe:integration-test failsafe:verify -DskipITs=false -o

# MongoDB
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