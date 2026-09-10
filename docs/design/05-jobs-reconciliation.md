# 定时任务、异步执行与对账详细设计

## 1. XXL-JOB 的职责

XXL-JOB 负责定时触发、路由、人工补跑、调度日志及失败告警；业务数据库负责任务身份、实际进度、结果与恢复。调度返回成功只说明触发/handler 阶段完成，不等于全部业务分片已完成。

inbound/outbound/inventory各自持有worker与任务表，只能写自己的数据库。新增sourcePostingRecovery（入/出库各自）、inventoryCommandRecovery、permitReconciliation，处理三段事务缺口与执行未知结果。

每个环境一个逻辑调度域，可按区域进一步隔离；生产 admin 多实例及数据库高可用按版本验证。每 Cell 使用独立 executor group，作业名使用 `wms-<env>-<cell>-<role>`。调度库与业务库独立账号/schema；访问令牌由受控凭据注入。只注册版本化 BEAN handler，禁用业务人员任意脚本改库存。

XXL-JOB 当前官方提供分片广播与重试等能力，但分片索引随执行器拓扑变化，不应作为业务任务的永久身份。[官方说明](https://github.com/xuxueli/xxl-job)

## 2. 任务目录和初始配置

下列频率/批次是开发验收起始值，不是生产 SLA；使用类型化配置、上限校验和变更审计。按 Cell/仓隔离并发，不给全国每张订单注册独立定时任务。

| Handler | 触发提案 | 分片与索引 | 业务行为与保护 |
| --- | --- | --- | --- |
| tccReservationWatch | 每 10 秒 | 仓+状态/更新时间 | 仅巡检TRIED/CONFIRMED和TC状态并告警；禁止按TTL释放或自行调用Cancel |
| allocationRecoverySweep | 每 30 秒 | 全局订单逻辑桶；state,next_retry_at | 查询TC终态与仓级结果、补齐业务完成Outbox；二阶段重试由TC负责 |
| expiryEligibilitySweep | 每 5 分钟 | 仓+效期窗口；expires_at,id | 标记/通知过期及受影响预占；接口仍实时校验效期 |
| serialTransferRecovery | 每 30 秒 | 序列号逻辑桶+超龄状态 | 根据源释放/目的接收事实推进；无事实不重新授权 |
| deviceUnknownResultSweep | 每 30 秒 | 设备+命令状态 | 有限查询，未知转人工；不盲目重发物理动作 |
| stockInternalReconcile | 小时增量+日关闭窗口 | 仓+SKU 范围+稳定 cutoff | 余额/流水/预占/序列号核对，差异落单 |
| externalReconcileExport | 可配置业务窗口 | 场景+仓+cutoff | 生成可重拉 manifest，与对账系统交换状态 |
| countApplyRecovery | 每 30 秒 | 盘点单+分片 | 继续未提交调整；不能重复已生效 adjustment |
| archivePlanner | 日触发 | 表+仓+关闭时间范围 | 仅生成归档计划/导出；删除另受控审批 |
| jobLeaseRecovery | 每 30 秒 | state,lease_until,id | 递增领取 epoch；旧执行者提交被拒绝 |

Outbox Publisher 和 Kafka Consumer 是持续工作进程，不依赖每秒注册大量 Cron。XXL-JOB 可触发积压巡检和补偿任务。

建议初始单 shard 每事务 100 行、单 handler 最多运行 30 秒再保存续跑状态；页大小、并发、任务运行预算须在 S0/S9 校准。不要让 XXL 重试与业务重试各自无限叠加：调度重试只重投相同 runKey，业务重试由 job_shard 的统一次数/截止时间控制。

## 3. 持久化执行协议

业务 runKey=`enterprise/jobType/scope/window/inputVersion`。shardKey 是稳定仓/逻辑桶/区间，不能用“当前执行器总数取模”形成永久任务 ID。并发创建 run 由唯一键收敛。

1. Planner 记录输入版本、仓/分片清单、预计工作量，分批创建 shard；规划完成才标记 PLANNED，避免误把尚未生成的分片当完成。
2. Executor 通过数据库条件更新领取 READY/可重试分片，设置 lease_owner、lease_until、递增 claim_epoch。
3. 执行前读取不可变 inputVersion，按游标有界取数；远程调用使用稳定clientOperationId/Idempotency-Key，保存服务端返回operationId。本地效果同样持久化唯一操作映射。
4. 同库任务在一个事务写业务效果、幂等结果和 checkpoint；提交时检查 claim_epoch 仍有效。远程任务不能同事务提交时，目标提供幂等操作和状态查询，确认结果后推进游标。
5. 领取时序与业务锁保持一致；旧 worker 提交必须被数据库条件/锁协议拦截，不能仅在开始时检查租约。
6. 重试失败记录错误类型、次数、下次时间；达到上限转 QUARANTINED。取消只停止未开始或未提交的分片，已提交效果保留。
7. run 完成以已规划 shard 总数和终态统计为准；部分失败显示 PARTIAL_FAILED，不能因 handler 正常返回而变 SUCCESS。

分页需避免“处理同时删除候选后 OFFSET 翻页”遗漏。使用稳定键游标+高水位；新进入候选范围的数据由下一窗口处理。重叠窗口按操作幂等去重。

## 4. 内部库存核对

| 核对 | 公式/依据 | 差异处理 |
| --- | --- | --- |
| inventory余额与流水 | 期初余额+已关闭窗口 delta=期末余额，reserved 同理 | 校验窗口和版本后再判差；不直接重写余额 |
| 预占与余额 | 桶 reserved=所有有效预占 remaining 之和 | 排除已提交迁移中间视图；异常锁定范围并追溯操作 |
| 序列号与数量 | 序列号商品桶量=对应有效 local_serial 数 | 核对登记 epoch、待登记 HOLD 和在途状态 |
| 来源执行与库存效果 | source_execution与inventory stock_posting按commandId/businessEffectKey关联 | 区分physical已做、库存PENDING、已过账回执未到；三服务分别有提交/消费watermark |
| 调拨守恒 | issued=received+loss_confirmed+remaining_in_transit | 未到货可为正常在途；到期才按规则告警 |

不能拿不同时间点的实时余额和未完整消费的流水比较。小范围本地核对可在短只读一致性快照中完成；大范围使用版本化库存快照和各分片提交高水位，不维持全国长事务。数值 ID 最大值不保证提交顺序，cutoff 需使用受控快照/CDC 位点/已关闭批次 manifest，保留来源说明。

## 5. 现有 recon-platform 接入调查

已只读检查本地 `recon-platform`，HEAD=`ee3b070`（工作树文件为实际检查对象，未承诺其无未提交改动）：

- SourceAdapter 提供 `sourceId/supports/open`，返回惰性游标，适合流式适配。
- DiscrepancyEvaluator 是可插拔判差接口，但入参 MatchGroup 使用现有领域记录。
- ReconRecord 强制持有 Money，Money 的值为 currency + long amountMinor；数量小数精度、单位、批次/序列号和库存位置不是现有一等模型。
- `/recon/runs`、`/recon/discrepancies` 和 `/recon/scenarios` 等查询/配置能力存在；不能据此推断存在仓储事实接收端点。

源码位置与链接见 [来源文档](07-decisions-evidence.md)。因此本版结论是“可复用对账平台的运行/差异管理思路，库存数量场景需要扩展”；不得把 EA、KG 填进 currency，或乘固定倍率伪装 amountMinor。

## 6. 对账契约与适配方案

建议新增独立 quantity 场景类型与数量事实模型，保留原 Money 路径不变。新增源适配、匹配/判差实现与差异详情，并验证所有既有金额场景回归。具体 recon 模块落点在 S7 通过源码依赖评审确定，不在本次改该仓库。

数量事实 WarehouseQuantityFact v1：

| 字段 | 类型/含义 |
| --- | --- |
| factId / factVersion | 稳定事实身份与修订版本；修正生成新版本，保留原始血缘 |
| enterpriseId / sourceSystem | 权限和来源；由认证范围核验 |
| scenarioCode / side | 场景及来源侧，如 WMS/ERP |
| warehouseId / ownerId / skuId | 库存归属维度 |
| businessLotKey / sourceLotId / serialId | 跨仓比较稳定批次身份，内部lotId仅追溯；按场景必填 |
| documentNo / documentLineNo / sourceCommandId / sourceOperationId / inventoryOperationId | 来源单据、行、命令与两侧操作身份 |
| physicalStatus / stockSyncStatus / factKind | 实物进度、库存同步进度、PHYSICAL或POSTED事实类型，不能混合比较 |
| quantity / unit / conversionVersion | 十进制字符串、基础单位、换算依据 |
| businessStatus / direction | 业务状态与 RECEIPT/SHIPMENT/ADJUSTMENT |
| occurredAt / postedAt | 实物动作时刻与过账时刻，UTC |
| cutoffId / sourceWatermark / rawRef | 稳定窗口、提交边界和可追溯来源 |

首期接入采用异步快照文件+manifest，避免假设 recon 已有通用 MQ 事实接口。WMS 提供 `/reconciliation-snapshots`；对账侧新增 SourceAdapter，通过服务身份读取对象存储授权文件。manifest 含 schemaVersion、企业/仓范围、cutoff、分片清单、每片行数/hash、单位清单和完成标志。导出全部完成后才发布完成 manifest；重拉同 snapshotId 返回相同内容。

后续可增加事件增量接收与缺口补拉；事件 ingestion API/topic 属于待新增契约，不能直接调用虚构现有接口。

场景：

- `WMS_ERP_RECEIPT_QTY`：按收货业务关联键比较单位一致的实收数量及状态。
- `WMS_OMS_SHIPMENT_QTY`：按履约子单/发运行比较已发数量；OMS 销售原单须先拆分映射。
- `WMS_TRANSFER_QTY`：按调拨行核对发出、接收、已审批损耗与在途，支持部分接收；active_receipt_quota是尚未确认接收的额度占用，不是额外实物量，禁止再次加进企业总库存。
- `WMS_SERIAL_OWNERSHIP`：按序列号身份比较有效归属与本地授权状态；不能只按总数相等判定一致。

差异类别：MISSING_LEFT/RIGHT、QTY_MISMATCH、UNIT_MISMATCH、LOT_MISMATCH、SERIAL_CONFLICT、STATE_MISMATCH、LATE_ARRIVAL、SOURCE_INCOMPLETE。迟到宽限值按对账场景配置，初始建议 15 分钟仅用于联调；缺少任一侧完成水位时标记待齐，不能正式结案为丢失。

差异状态：OPEN -> INVESTIGATING -> PENDING_APPROVAL -> REMEDIATING -> VERIFYING -> CLOSED；另有 REJECTED/MANUAL_REVIEW。补拉/重投事件可自动处理，但库存调整、报损必须审批并调用 WMS 业务命令。对账服务账号没有 WMS 库存表写权限。

接入上线门禁：明确 quantity 能力边界 → recon 扩展及金额回归 → 契约测试 → 双方隔离环境联调 → 差异修复后重新核对 → 记录双方提交/版本和回退顺序。recon 未扩展时 WMS 内部核对仍可实现，但跨系统对账验收保持未完成。

## 7. v0.3恢复补充

三服务分别产生Outbox/inbox与幂等墓碑；对账cutoff同时涵盖来源事实提交、inventory处理和来源结果消费水位。inventory内部核对需包含free_execution_claim_qty及已预占inflight子集；冻结快照须证明相关STARTED/UNKNOWN permit已清零。外部修复请求路由到业务拥有者，不能由调度执行器持有三库写权限。

## v0.4启动与重授权恢复约束

allocationRecoverySweep按[幂等专项](10-idempotency-protocols.md)查询已绑定XID，不重新遍历Try；仅未绑定且经epoch隔离证明无分支的空启动允许重新领取。调度器通过fulfillment受控接口请求清理已知空事务，未知空事务等待TC超时，不直接修改TC表。来源恢复任务复用effect/command/compensationCasePart，REJECTED后的安全关闭单独持久化；重复跑任务不得创建新补偿事实或跳过未知执行状态。
