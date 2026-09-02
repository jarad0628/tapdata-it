# TapData 引擎（iengine）集成测试维度设计

> 版本：v1.1（对齐[集成测试框架落地计划](./集成测试框架落地计划.md) v2.0：新增核心用例集 L1E-01~10 与工作量预估、执行环境与里程碑对齐排期、CI 每次提交触发 IT 的门禁策略）
> 适用代码库：`tapdata`（社区版引擎）、`tapdata-enterprise`（企业版引擎扩展）
> 目标模块：`tapdata-it` 聚合工程下新建 `engine-it` 模块（待建）
> 关联文档：[集成测试框架落地计划](./集成测试框架落地计划.md)、`tapdata-it.md`

## 1. 设计哲学

### 1.1 测试目标

引擎 IT 验证的是**引擎侧的任务执行路径是否正确**——从任务启动、数据流转、断点续跑、DDL 同步、并发写到错误处理，验证引擎作为"编排层"对连接器能力的调用时序、数据一致性保证、以及各旋钮的实际行为：

```
TaskDto 配置 → 引擎 DAG 编译 → Jet 执行 → 断言端到端行为（数据/状态/offset/日志）
```

测试边界：引擎 IT 不验证连接器能力函数本身的正确性（不属于引擎职责），只验证引擎对连接器的调用时序与数据流转。

### 1.2 核心原则

| 原则 | 引擎 IT 中的体现 |
|---|---|
| **契约驱动** | 每个维度锚定引擎行为契约（wiki CT-*/M-* 条目），用例即契约的可执行化 |
| **数据验证** | 源端写入→目标端读回→逐行逐列一致性断言（旁路验证，不依赖任务自身指标） |
| **独立隔离** | 独立任务实例 + 独立数据库 + 随机表名前缀 + `@AfterEach` 清理 |
| **能力跳过** | 按任务类型/配置自动跳过不适用的维度，避免误报 |
| **边界断言** | 断点粒度、顺序保证、静默失效、丢数据边界等"反直觉"行为显式用例化 |

### 1.3 测试形态

引擎 IT 不是单元测试（不 mock 内部组件），也不是端到端测试（不经过 TM Web API）。它是**引擎进程级的集成测试**：

- **输入**：构造 `TaskDto`（任务配置）+ 真实源/目标数据库
- **执行**：启动引擎节点（`HazelcastTaskService` 建 DAG → Jet 执行）
- **断言**：验证目标端数据、任务状态、offset 持久化、日志行为
- **CI 定位**：最终集成到 CI 流程中，**每次提交（push/PR）都触发执行 IT**——通过分级执行策略（见 5.4）控制单次提交的门禁耗时，引擎 L1 用例是提交级 PR 门禁的组成部分

## 2. 测试维度总览

按引擎行为契约分为 **12 个维度**，每个维度对应 wiki 中一组 CT/M 条目：

| # | 维度 | 核心契约来源 | 验证性质 |
|---|---|---|---|
| D1 | 任务生命周期 | M-任务调度与生命周期 | 状态流转正确性 |
| D2 | 全量读取路径 | M-全量读的三条路径、CT-全量读的断点 | 路径选择 + 断点行为 |
| D3 | 增量读取与断点 | CT-增量断点与offset | offset 生命周期 |
| D4 | 目标端写入 | M-目标端写入的主循环、M-目标端的写入语义、M-一批事件怎么写到目标端 | 数据一致性 |
| D5 | DDL 同步 | CT-DDL的同步边界、M-DDL的过滤传播与落库 | 三层闸行为 |
| D6 | 事件顺序保证 | CT-事件顺序的保证范围 | 保序/乱序边界 |
| D7 | 事件不丢的边界 | CT-事件不丢的边界 | 背压 + 有意丢弃 |
| D8 | 写入策略与唯一键 | M-写入策略与唯一键冲突 | 自适应策略 |
| D9 | 自动建表与类型映射 | M-自动建表与类型映射 | 四级回落 + 建表分派 |
| D10 | 并发写与分区 | M-并发写与分区 | 保序粒度 + 静默关闭 |
| D11 | 任务级重试与错误处理 | CT-任务级重试的边界、CT-错误与重试 | 重试边界 |
| D12 | 引擎旋钮与静默失效 | CT-引擎旋钮的作用域与静默失效 | 开关实际生效 |

### 2.1 核心用例集（L1E-01~10，对齐落地计划）

《集成测试框架落地计划》v2.0 在 D1-D12 维度之上收敛出 **10 个核心用例**（L1E-01~10），作为 engine-it 模块的落地范围、排期与工作量估算单元。每个核心用例是若干维度用例的"落地切片"，映射关系与预估如下（纯人工口径）：

| # | 核心用例 | 覆盖维度 | 测试目的 | 测试方法 | 预估 |
|---|---|---|---|---|---|
| L1E-01 | 任务启动与 DAG 编译 | D1 | 验证 TaskDto → DAG 编译正确、节点按类型（source/processor/target）装配 | 构造 mock→mock 任务启动，断言节点实例化与 DAG 拓扑、任务进入运行态 | 3 天 |
| L1E-02 | 全量同步数据一致 | D2、D4 | 验证全量读取→写入链路数据完整 | 源端预置 N 行（多类型字段），启动同步任务后旁路比对目标端行数与内容 | 3 天 |
| L1E-03 | 增量同步与事件类型 | D3、D6 | 验证增量 DML 事件按序到达目标端 | 任务运行中在源端执行 insert/update/delete，Awaitility 轮询目标端断言事件与顺序 | 3 天 |
| L1E-04 | 断点续跑 | D3、D7 | 验证任务停止/重启后从 offset 续传、不重不丢 | 全量/增量过程中停止任务→源端继续写→重启任务→断言目标端最终一致且 offset 持久化 | 3 天 |
| L1E-05 | 目标端写入策略 | D8 | 验证唯一键冲突时按写入策略（insert_on_exists/update/ignore）执行 | 同一任务配置不同写入策略分别运行，冲突场景下旁路断言目标端结果 | 2 天 |
| L1E-06 | 自动建表与类型映射 | D9 | 验证无目标表时自动建表、字段类型按映射规则生成 | 源端建表后直接启动任务（不预建目标表），旁路断言目标表结构与类型 | 2 天 |
| L1E-07 | DDL 同步 | D5 | 验证 DDL 事件按过滤配置传播/落库（三层闸） | 任务运行中对源端执行 add field/drop field 等 DDL，断言目标端结构变化与过滤规则生效 | 3 天 |
| L1E-08 | 任务停止与状态终态 | D1 | 验证停止指令后资源释放、taskhistory 状态为终态 | 运行中下发停止，断言节点关闭、连接释放、状态机进入终态 | 2 天 |
| L1E-09 | 处理器节点（JS/字段加工） | D4（processor 节点） | 验证 processor 节点对事件流的加工正确 | 任务含 JS 算子/字段映射/过滤配置，源端写入后断言目标端数据经过处理 | 2 天 |
| L1E-10 | 错误注入与任务级重试 | D11 | 验证源/目标故障时任务按重试边界恢复或失败 | 注入网络中断/错误凭据，断言重试次数、退避、最终状态与告警事件产生 | 3 天 |

**工作量口径**：模块骨架 + 10 个核心用例合计约 **26 人天**（纯人工）；人+AI 模式约 **16 人天**（压缩 ~38%）——用例骨架与数据构造 AI 生成，Hazelcast/Jet 真实执行联调、Flaky 归因人工为主。schema/generator/assert 等测试资产复用 `tapdata-connector-it` 已有实现（见 4.3）；各维度内未被核心用例覆盖的细分用例（如 D6 保序边界、D12 旋钮），随 M3/M4 阶段按维度补齐。

---

## 3. 各维度用例设计

### D1. 任务生命周期

> 契约来源：`M-任务调度与生命周期`

| # | 用例 | 验证点 | 断言 |
|---|---|---|---|
| D1.1 | should_start_task_from_wait_run | 任务从 `wait_run` 被引擎认领并启动 | 任务状态变为 `running`，Jet Job 存在 |
| D1.2 | should_idempotent_start | 同一任务重复发启动信号 | 第二次启动只刷状态，不重复建 DAG |
| D1.3 | should_graceful_stop | 外部停止信号 | 任务状态变为 `stopped`，Jet Job 取消 |
| D1.4 | should_natural_complete | 全量任务跑完 | 任务状态变为 `complete`，且活过 5 秒后才标记 |
| D1.5 | should_error_then_retry | 运行中异常 | 可重试时自动重启，不可重试时落 `runError` |
| D1.6 | should_terminal_mode_priority | 停止信号后出错 | `STOP_GRACEFUL` 优先于 `ERROR`，最终状态为 `stopped` |
| D1.7 | should_reschedule_on_agent_change | TM 改派 `agentId` | 本地静默停，不改 TM 状态 |
| D1.8 | should_reconcile_assigned_tasks | 30 秒对账兜底 | `wait_run` 任务在对账周期内被接管 |
| D1.9 | should_engine_restart_recover_running | 引擎重启 | 之前 `running` 的任务被重新接管 |
| D1.10 | should_concurrent_start_semaphore | 多任务并发启动 | 超过 `Semaphore` 上限的任务排队等待，不丢失 |

### D2. 全量读取路径

> 契约来源：`M-全量读的三条路径`、`CT-全量读的断点`

| # | 用例 | 验证点 | 断言 |
|---|---|---|---|
| D2.1 | should_normal_snapshot_serial | 普通全量：逐表串行 | 表按顺序读完，已完成的表不重读 |
| D2.2 | should_concurrent_read_table_level | 多表并发读：表级并行 | N 个线程同时读不同表，单表仍串行 |
| D2.3 | should_partition_read_parallel | 分片并行读：增量先起、全量追赶 | 增量事件在全量未读完时按表拦住 |
| D2.4 | should_partition_read_per_table_enter_cdc | 分片读：逐表进入增量 | A 表已增量、B 表仍全量——这是正常状态 |
| D2.5 | should_snapshot_breakpoint_table_level | 断点粒度是「表」 | 断在第 7 张表，重启前 6 张不重读，第 7 张从头来 |
| D2.6 | should_snapshot_breakpoint_not_table_internal | 断点不保证表内位置 | 连接器侧写的 offset 不被引擎使用（恒传 null） |
| D2.7 | should_priority_partition_over_concurrent | 分片读优先于多表并发 | 两个开关同时开，只有分片读生效 |
| D2.8 | should_silent_fallback_to_table_level | 连接器未注册 `getReadPartitions` | 静默退回表级，无日志 |
| D2.9 | should_snapshot_complete_event | 全量完成事件 | 全部表读完后发出，下游据此切换并发写策略 |

### D3. 增量读取与断点

> 契约来源：`CT-增量断点与offset`

| # | 用例 | 验证点 | 断言 |
|---|---|---|---|
| D3.1 | should_stream_offset_real_value | `streamRead` 收到真实 offset | 非 null，且连接器可解析 |
| D3.2 | should_offset_advance_after_write | offset 在写到目标端后推进 | 目标端写入完成 → `flushOffsetCallback` 推进 |
| D3.3 | should_offset_persist_every_10s | offset 每 10 秒持久化一次 | 崩溃重启最多丢 10 秒进度 |
| D3.4 | should_timestamp_to_stream_offset | 从时间点起增量 | 调连接器 `timestampToStreamOffset` 获取起始 offset |
| D3.5 | should_stop_after_snapshot_without_timestamp_fn | 无 `timestampToStreamOffset` 能力 | 全量做完后停住，日志只有 warn |
| D3.6 | should_offset_not_advance_without_source_time | `sourceTime` 为空 | 整批不推进 offset |
| D3.7 | should_restart_replay_data | 重启重放 | 重启后从上次持久化的 offset 开始，数据会重复读一段 |
| D3.8 | should_offset_format_connector_specific | offset 形状因连接器而异 | MySQL/Oracle/MongoDB 各自返回不同结构 |

### D4. 目标端写入

> 契约来源：`M-目标端写入的主循环`、`M-目标端的写入语义`、`M-一批事件怎么写到目标端`

| # | 用例 | 验证点 | 断言 |
|---|---|---|---|
| D4.1 | should_two_level_queue_batch | 两级队列攒批 | 事件经 `tapEventQueue` → `tapEventProcessQueue` 两级队列 |
| D4.2 | should_ddl_barrier_first_level | DDL 第一道栅栏 | DDL 前的 DML 全部写完才放行 DDL |
| D4.3 | should_ddl_barrier_second_level | DDL 第二道栅栏 | DDL 单独成一批，不与 DML 混合写入 |
| D4.4 | should_full_to_cdc_switch_within_batch | 全量→增量切换在批内 | 标记事件前的按全量写、后的按增量写 |
| D4.5 | should_write_group_by_table_scramble_order | 按表分组打乱跨表顺序 | `HashMap` 遍历序 ≠ 事件原始序 |
| D4.6 | should_write_adjacent_segment_preserve_order | 按相邻切段保序 | 段间先后 = 事件原始先后 |
| D4.7 | should_group_by_table_default_opposite_for_migrate | 迁移任务默认不按表分组 | `writeGroupByTableEnable` 字段初值 `true`，但库级节点没配 = `false` |
| D4.8 | should_skip_error_table_silent | 跳过错误表静默丢弃 | 命中跳过表后整批 `return`，无日志 |
| D4.9 | should_error_map_not_affect_control_flow | `errorMap` 不影响控制流 | 连接器放进 `errorMap` 的错误，引擎当本批成功 |
| D4.10 | should_write_record_six_gates | `writeRecord` 六道关卡 | 目标表名空→抛错；跳过表→静默丢；不支持字段→摘除 |
| D4.11 | should_skip_error_data_degrade_to_single_write | 跳过错误数据降级逐条写 | 吞吐下降数量级 |
| D4.12 | should_data_consistent_end_to_end | 端到端数据一致性 | 源端写入 N 条 → 目标端读回 N 条 → 逐行逐列比对 |

### D5. DDL 同步

> 契约来源：`CT-DDL的同步边界`、`M-DDL的过滤传播与落库`

| # | 用例 | 验证点 | 断言 |
|---|---|---|---|
| D5.1 | should_ddl_three_gates | 三层闸 | ① 源端过滤 → ② 目标端引擎 handler → ③ 目标端连接器函数 |
| D5.2 | should_ddl_filter_mode_silent_drop | "过滤"档丢弃 DDL | 任务正常，DDL 不到目标端 |
| D5.3 | should_ddl_error_mode_fail_task | "报错"档让任务失败 | 抛异常，任务落 `runError` |
| D5.4 | should_ddl_null_config_silent_drop | DDL 配置为 null | 静默丢弃，既不同步也不报错 |
| D5.5 | should_ddl_sync_mode_disabled_events_null | "同步"档但 `disabledEvents` 为 null | 全部丢弃（与直觉相反） |
| D5.6 | should_ddl_eight_handler_types | 目标端只支持八类 DDL | 新增字段/改字段名/改字段属性/删字段/建表/建索引/清表/删表 |
| D5.7 | should_ddl_unsupported_handler_warn | 八类之外的 DDL | `warn` + 跳过，任务继续 |
| D5.8 | should_ddl_connector_not_support_warn | 连接器未实现对应函数 | `warn` + 跳过，任务继续 |
| D5.9 | should_ddl_global_sync_point | DDL 是全局同步点 | DDL 经过每层都退回串行 |
| D5.10 | should_union_drop_all_ddl | 合并表算子无条件丢弃 DDL | 过了源端过滤的 DDL 在算子层消失 |
| D5.11 | should_ddl_model_diverge_from_target | 模型与目标库不一致 | DDL 被 ②③ 跳过后，内存模型已更新但目标库未变 |

### D6. 事件顺序保证

> 契约来源：`CT-事件顺序的保证范围`

| # | 用例 | 验证点 | 断言 |
|---|---|---|---|
| D6.1 | should_jet_local_parallelism_one | Jet 节点并行度 = 1 | 每条边单生产者→单消费者 FIFO |
| D6.2 | should_processor_concurrent_preserve_order | 算子并发不亂序 | 轮转投递 + 轮转取回，取回序 = 投递序 |
| D6.3 | should_cdc_concurrent_write_row_order | 增量并发写行级保序 | 同键同分区→同线程→有序 |
| D6.4 | should_delete_null_key_fallback_serial | delete 分区键有 null | 发栅栏→全走 0 号分区→单线程模式 |
| D6.5 | should_ddl_barrier_in_concurrent_write | DDL 在并发写中是栅栏 | DDL 落 0 号分区，等所有分区处理完 |
| D6.6 | should_update_partition_key_change | update 改分区键 | 用 `before` 哈希查缓存，跟着原分区走 |
| D6.7 | should_cross_table_order_not_guaranteed_with_group | 按表分组不保跨表序 | `HashMap` 遍历序 |
| D6.8 | should_full_concurrent_write_random_partition | 全量并发写随机分区 | `random.nextInt(partitionSize)`，不保序 |
| D6.9 | should_no_pk_table_single_partition | 无主键表全落 0 号分区 | 并发度实际为 1 |
| D6.10 | should_offset_advance_after_all_partitions | offset 在所有分区处理完后推进 | 断点不跑到没写完的数据前面 |

### D7. 事件不丢的边界

> 契约来源：`CT-事件不丢的边界`

| # | 用例 | 验证点 | 断言 |
|---|---|---|---|
| D7.1 | should_six_delivery_points_no_drop | 六个投递点不丢 | 队列满时循环重试，背压顶回源库 |
| D7.2 | should_backpressure_propagate_to_source | 背压传播到源端 | 目标端慢 → 队列满 → 源端不再拉新数据 |
| D7.3 | should_restart_replay_not_lose | 重启不丢数据 | offset 未推进，重启重放 |
| D7.4 | should_ddl_filter_silent_drop | DDL 过滤静默丢 | 配置为空时丢弃，只有 warn |
| D7.5 | should_union_drop_ddl | 合并表算子丢 DDL | 只有 info 日志 |
| D7.6 | should_exactly_once_dedup_drop | exactly-once 去重丢 | 命中去重 id 后事件被丢弃，只有 trace |
| D7.7 | should_skip_error_table_silent_drop | 跳过错误表静默丢 | 整批 return，无日志 |
| D7.8 | should_queue_shrink_may_drop | 队列缩容可能丢 | `DynamicLinkedBlockingQueue.migrate` 中 `!newQ.offer(e)` → 静默丢 |
| D7.9 | should_field_level_drop | 字段级丢弃 | 自动屏蔽新增字段 / 目标端不支持字段被移除 |
| D7.10 | should_metric_not_reliable_for_reconciliation | 指标不能用于对账 | 采样/上报均有丢弃，指标是尽力而为 |

### D8. 写入策略与唯一键冲突

> 契约来源：`M-写入策略与唯一键冲突`

| # | 用例 | 验证点 | 断言 |
|---|---|---|---|
| D8.1 | should_insert_first_then_switch | 先纯插入后切换 | 配"存在即更新"，第一次仍按纯 insert 写 |
| D8.2 | should_unique_violation_trigger_switch | 唯一键冲突触发切换 | 捕获异常 → 回滚事务 → 策略切成"存在即更新" → 重写 |
| D8.3 | should_consecutive_conflict_threshold | 连续冲突阈值 | 超阈值后该表后续批次直接用"存在即更新" |
| D8.4 | should_success_reset_conflict_count | 成功清零计数 | 纯插入成功一批 → 连续冲突计数归零 → 恢复试探 |
| D8.5 | should_per_table_tracking | 按表独立追踪 | A 表切过去了，B 表还在试探 |
| D8.6 | should_direct_insert_no_adaptive | 直接插入不走自适应 | 配"直接插入"时跳过策略逻辑 |

### D9. 自动建表与类型映射

> 契约来源：`M-自动建表与类型映射`

| # | 用例 | 验证点 | 断言 |
|---|---|---|---|
| D9.1 | should_four_level_type_fallback | 四级类型回落 | 用户 codec → 打分匹配 → 不合格中最佳 → 最大字符串类型 |
| D9.2 | should_best_in_unmatched_warn | 装不下也选 | 得分为负时选最不差的 + warn `BEST_IN_UNMATCHED` |
| D9.3 | should_pk_type_exclude_min_score | 主键类型排除 | 目标类型不能做主键 → `MIN_SCORE` 排除 |
| D9.4 | should_create_table_six_paths | 建表六条路径 | 禁用→跳过；分区子表→跳过/降级；普通表→建；两函数都没有→不建不报错 |
| D9.5 | should_create_table_v2_return_exists | V2 返回表是否存在 | `CreateTableOptions.tableExists` 影响下游索引创建 |
| D9.6 | should_exists_data_process_cdc_skip | 纯增量任务跳过删表/清数据 | `syncType == CDC` 时 `dropTable`/`clearData` 不执行 |
| D9.7 | should_foreign_key_four_conditions | 外键约束四条件 | 少一条就整段跳过，不报错 |
| D9.8 | should_partition_table_degrade_to_normal | 分区表类型非法降级 | 主分区表降级成普通表建；子分区表跳过不建 |

### D10. 并发写与分区

> 契约来源：`M-并发写与分区`、`M-目标端写入的主循环`

| # | 用例 | 验证点 | 断言 |
|---|---|---|---|
| D10.1 | should_cdc_concurrent_four_silent_close | 增量并发写四个静默关闭条件 | 并发数≤1 / 配置关 / 上游有合并或展开节点 / 共享挖掘 |
| D10.2 | should_full_concurrent_random | 全量并发写随机分区 | `random.nextInt(partitionSize)` |
| D10.3 | should_cdc_concurrent_by_key | 增量按分区键分派 | 配了分区字段用字段，没配用逻辑主键 |
| D10.4 | should_memory_pressure_disable_concurrent | 内存压力关闭并发写 | 收到"降低"事件→停全量并发写；"提高"→重建 |
| D10.5 | should_concurrent_processor_watermark | watermark 保序 | offset 在所有分区处理完后才推进 |
| D10.6 | should_shared_mining_table_level_partition | 共享挖掘表级分区 | 分区键是表名，一张表一条线程 |

### D11. 任务级重试与错误处理

> 契约来源：`CT-任务级重试的边界`、`CT-错误与重试`

| # | 用例 | 验证点 | 断言 |
|---|---|---|---|
| D11.1 | should_task_retry_cdc_only | 只有 CDC 阶段自动重试 | 全量阶段出错 → `runError`，不重试 |
| D11.2 | should_task_retry_time_window | 重试时间窗口 | 从第一次重试开始计，不是每次出错重新计 |
| D11.3 | should_task_retry_no_count_limit | 无次数上限 | 只有时间窗口，5 秒扫一次 |
| D11.4 | should_pure_cdc_always_retryable | 纯增量任务直接可重试 | `syncType == CDC` 跳过进度检查 |
| D11.5 | should_method_retry_capped_by_task | 方法级被任务级封顶 | 任务窗口耗尽后方法级重试归零 |
| D11.6 | should_one_hour_reset_window | 1 小时重置 | 从重试起点满 1 小时才 reset 重试状态 |
| D11.7 | should_skip_error_table_never_add_in_cdc | 增量阶段不新增跳过表 | `SyncStage.CDC` → `return false` |
| D11.8 | should_skip_error_table_final_fail | 全量结束后秋后算账 | 跳过数 > 0 → sleep 5 秒 → 抛 `HAS_SKIP_ERROR_TABLE` |
| D11.9 | should_two_layer_retry_nested | 两层重试嵌套 | 方法级用尽 → 任务级接手（停掉再启动） |

### D12. 引擎旋钮与静默失效

> 契约来源：`CT-引擎旋钮的作用域与静默失效`

| # | 用例 | 验证点 | 断言 |
|---|---|---|---|
| D12.1 | should_process_level_knob_affect_all_tasks | 进程级旋钮影响所有任务 | `System.getProperty` 无任务维度 |
| D12.2 | should_dynamic_batch_three_conditions | 动态批次三条件 | 任务开关 + `StreamReadOneByOneFunction` + 增量阶段 |
| D12.3 | should_dynamic_batch_silent_when_unsupported | 连接器不支持时静默 | 直接 return，无日志 |
| D12.4 | should_concurrent_processor_whitelist | 算子并发白名单 | 基类返回 `false`，逐算子覆写 |
| D12.5 | should_null_config_direction_varies | 空值语义方向各不相同 | 动态批次=关；按表分组=与初值相反；DDL=静默丢弃 |
| D12.6 | should_info_log_means_enabled | info 日志=已启用 | 启用打 `info`；不启用打 `trace` 或什么都不打 |
| D12.7 | should_group_by_table_default_for_database_node | 按表分组在库级节点默认 false | 字段初值 `true`，但 `Boolean.TRUE.equals(null)` = `false` |

---

## 4. 测试基础设施需求

### 4.1 引擎 IT 基类 `EngineIT`

```java
public abstract class EngineIT {

    // ===================== 子类扩展点 =====================

    /** 子类提供：源端连接配置 + 目标端连接配置 + 已初始化的连接器 */
    protected abstract EngineTestContext createContext() throws Throwable;

    /** 子类可覆写：任务类型（默认 migrate） */
    protected String defaultSyncType() { return "migrate"; }

    /** 子类可覆写：测试表名前缀 */
    protected String tablePrefix() { return "_tap_eit_"; }

    // ===================== 生命周期 =====================

    @BeforeEach
    void setUp() throws Throwable {
        context = createContext();
        // 1. 准备源端/目标端真实数据库连接
        // 2. 创建引擎节点（HazelcastTaskService）
        // 3. 构建最小 DAG（source → target）
        // 4. 不启动 TM，直接通过 TaskService 操控任务
    }

    @AfterEach
    void tearDown() throws Throwable {
        // 1. 停止任务
        // 2. 清理源端/目标端测试表
        // 3. 释放引擎资源
    }

    // ===================== 工具方法 =====================

    /** 构造最小可执行任务 */
    protected TaskDto buildMinimalTask(String syncType, List<String> tables);

    /** 等待任务到达指定阶段 */
    protected void awaitSyncStage(String stage, Duration timeout);

    /** 比对源端与目标端数据 */
    protected void assertDataConsistent(String table, List<String> primaryKeys);
}
```

### 4.2 EngineTestContext

```java
public class EngineTestContext {
    /** 源端 Connector 实例 */
    private final TapConnector sourceConnector;
    /** 目标端 Connector 实例 */
    private final TapConnector targetConnector;
    /** 源端连接配置 */
    private final DataMap sourceConfig;
    /** 目标端连接配置 */
    private final DataMap targetConfig;
    /** 引擎 TaskService（直接操控，不经 TM） */
    private final HazelcastTaskService taskService;
    /** 测试表规格 */
    private final TestTableSpec tableSpec;
    /** 任务 DTO */
    private TaskDto taskDto;
}
```

### 4.3 测试资产复用

数据构造与断言等通用测试资产复用 `tapdata-connector-it` 模块的既有实现，engine-it 通过 Maven 依赖引入并按需扩展：

```
tapdata-it/
├── tapdata-connector-it/        ← 既有：TestDataType / TestFieldSpec / TestTableSpec、
│                                    RandomDataFactory / ValueGenerator、
│                                    RecordAssert / TableAssert、TestLog / TestStateMap
└── engine-it/                   ← 待建：引擎行为验证（EngineIT.java）
    ├── schema/                  ← 复用：TestDataType / TestFieldSpec / TestTableSpec
    ├── generator/               ← 复用：RandomDataFactory / ValueGenerator 体系
    ├── mapping/                 ← 复用：TapTypeResolver
    ├── assert/                  ← 复用 + 扩展：RecordAssert / TableAssert + 新增 TaskAssert / OffsetAssert
    └── support/                 ← 复用：TestLog / TestStateMap + 新增 TestTaskService
```

## 5. 测试执行策略

### 5.1 环境要求

| 组件 | 用途 | 配置方式 |
|---|---|---|
| MongoDB | 引擎状态存储（Hazelcast 持久化） | docker-compose |
| 源端数据库 | 被测数据源 | docker-compose 或预置环境 |
| 目标端数据库 | 被测数据目标 | docker-compose 或预置环境 |
| Hazelcast 集群 | 引擎 DAG 执行 | 内嵌模式（测试 scope） |

执行环境沿用组织级自托管 GitHub Actions runner（`docker/docker-compose.yaml` 编排，label `tapdata-it`，4 副本，privileged + docker.sock 挂载）；源/目标端测试数据库优先由 Testcontainers 在用例中动态拉起、用完销毁，MongoDB（引擎状态存储）等常驻组件由 docker-compose 提供。用例命名 `*IT.java`，`mvn verify`（Failsafe）执行。

### 5.2 执行方式

```bash
# 单维度执行（如只测 D3 增量断点）
mvn -pl engine-it -Dtest=EngineIT#should_stream_offset_real_value \
    -Dengine.it.source.type=mysql -Dengine.it.target.type=mysql \
    verify

# 全维度执行
mvn -pl engine-it \
    -Dengine.it.source.type=mysql -Dengine.it.target.type=mongodb \
    verify
```

### 5.3 矩阵策略

引擎 IT 的矩阵变量包含两个维度——**源→目标组合**与**任务类型**：

| 任务类型 | 影响维度 |
|---|---|
| 全量（snapshot） | D2、D4、D8、D9 |
| 全量+增量（migrate） | D1~D12 全覆盖 |
| 纯增量（cdc） | D3、D5、D6、D7、D11 |
| 同步（sync，单表） | D4、D5、D6、D8 |

建议首批覆盖 **migrate 类型**（维度最全），然后按类型补充专属用例。

### 5.4 CI 集成策略（每次提交触发 IT）

引擎 L1 IT 是 PR 门禁的组成部分：**每次提交（push/PR）都触发执行**。由于引擎用例依赖真实数据库与 Hazelcast/Jet 执行，全量矩阵单次耗时可达数十分钟，不能原样塞进提交级流水线，因此按触发频率分三级：

| 触发时机 | 执行范围 | 预期耗时 | 门禁语义 |
|---|---|---|---|
| **每次提交**（push/PR，同步执行） | 冒烟子集：L1E-01/02/03（DAG 编译、全量一致、增量事件）× 默认组合（MySQL→MySQL，migrate 类型） | 目标 < 15 分钟 | **阻断合并**：任一失败禁止合入，随 PR 状态展示 |
| **合入主干后 / 每日** | 10 个核心用例全量（L1E-01~10）× 常用组合（MySQL→MySQL、MySQL→MongoDB） | 30~60 分钟 | 失败告警，当日内修复；结果纳入 Daily 回归 |
| **每周 / 发布前** | 全矩阵：4 对源→目标交叉（M5）+ 全部维度细分用例（含 D6 保序边界、D10 并发写等大数据量用例） | 数小时 | 发布门禁，失败需归因后放行 |

实施要点：

1. **工作流分层**：提交级 job 只跑 `EngineITSuite`（JUnit tag/分组：`smoke`），每日与每周 job 通过同一工作流的不同 schedule/filter 复用同一套用例代码，避免维护两套脚本；
2. **并行分片**：自托管 runner（label `tapdata-it`，4 副本）按用例组分片并行，提交级冒烟集固定分到单副本跑，减少环境间干扰；
3. **环境供给**：提交级用例的源/目标库用 Testcontainers 动态拉起，注意 docker 资源上限（privileged + docker.sock 挂载已就绪）；
4. **Flaky 治理与门禁解耦**：时序类用例（D3/D6/D10）先以 `@Tag("flaky-candidate")` 挂每周级观察，稳定≥2 周后提升至提交级；门禁失败严禁用重跑掩盖，须先归因；
5. **提交级基线先行**：M1（框架骨架）完成时即接入空跑占位工作流（只验编译与环境拉起），确保 CI 链路先于用例规模扩张就绪，后续用例按里程碑逐步纳入提交级范围。

## 6. 风险与注意事项

1. **引擎 IT 需要真实数据库**：除源/目标端数据库外，还需要 MongoDB（引擎状态存储）和 Hazelcast 内嵌集群，环境搭建成本高
2. **任务构造的复杂性**：`TaskDto` 字段极多，需要维护一个"最小可执行任务"构造器，避免每个用例都从零搭建
3. **时序断言的稳定性**：引擎行为涉及多线程、定时器、队列，断言需要合理的超时与轮询策略，避免 flaky test
4. **静默行为的验证**：引擎大量"静默失效"行为（无日志或只有 trace），测试需要主动验证"什么都没发生"——这比验证"发生了什么"更难
5. **offset 验证依赖连接器**：offset 的形状由连接器定义，引擎 IT 需要至少一对已知 offset 行为的连接器（如 MySQL → MySQL）
6. **并发写测试需要大数据量**：验证保序/乱序边界需要足够多的数据行和足够长的运行时间，测试耗时可能较长
7. **企业版功能分支**：数据校验（D12 部分）、共享挖掘等仅企业版可用，需要通过 profile 控制
8. **测试边界**：引擎 IT 不验证连接器能力函数本身的正确性，只验证引擎对连接器的调用时序与数据流转；连接器自身缺陷归连接器侧测试体系处理

## 7. 里程碑规划

| 阶段 | 内容 | 产出 | 落地计划排期对齐 |
|---|---|---|---|
| M1 | 框架骨架：`EngineIT` 基类 + `EngineTestContext` + 最小任务构造器 + 生命周期管理 | engine-it 可编译，D1（生命周期）跑通 | P2（第 2 周）：模块骨架 + L1E-01；**CI 占位工作流同步建立**（每次提交触发空跑） |
| M2 | 核心数据路径：D2（全量读）+ D3（增量断点）+ D4（目标端写入）+ D7（数据一致性） | 端到端数据流转验证 | P2~P3（第 2~5 周）：第 2 周末交付 L1E-01/02/03，第 3-5 周交付 L1E-04~10 |
| M3 | DDL 与顺序：D5（DDL 同步）+ D6（事件顺序）+ D10（并发写） | DDL 三层闸 + 保序边界 | P3（第 3-5 周）：L1E-07 及 D6/D10 维度补齐 |
| M4 | 策略与错误：D8（写入策略）+ D9（自动建表）+ D11（重试）+ D12（旋钮） | 边界行为全覆盖 | P3（第 3-5 周）：L1E-05/06/10 及 D12 维度补齐 |
| M5 | 多连接器矩阵：MySQL/PostgreSQL/MongoDB/Oracle 交叉 | 至少 4 对源→目标组合 | P5（第 7-9 周） |
| M6 | CI 接入：提交级冒烟子集（每次提交触发 IT，阻断合并）+ 每日/每周分级工作流 + 维度报告 | 三级触发策略（5.4）全量落地，CI 流水线稳定运行 | P5 + 缓冲（第 7-10 周），随三层 CI 门禁常态化；提交级冒烟集自 M2 起即随用例交付逐步纳入 |

> 工作量口径：M1-M4（模块骨架 + 10 个核心用例）合计约 26 人天（纯人工）/ 16 人天（人+AI），对应落地计划 L1 引擎子项；M5-M6 对应 P5 的 CI 门禁与报告汇总。关键出口节点：第 5 周末 L1 全量完成（引擎 IT 首批用例随 PR 门禁运行）。
