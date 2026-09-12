# 库存确认到履约的可靠消息

库存在原 TCC Confirm 本地事务更新预占后，把原始身份写入 `ReservationConfirmed` Outbox。此处不创建另一套全局事务决定；TC 终态观察独立见 [FULFILLMENT_TC_RECOVERY.md](FULFILLMENT_TC_RECOVERY.md)。

## 契约与来源

新正文包含 `confirmationSchemaVersion=1`、reservationId、allocationId、attemptId、xid、branchId、actionName、routeEpoch、CONFIRMED 状态；信封的 aggregateId 和 aggregateVersion 分别来自预占头 ID 和确认后的版本。字段从已锁定的原预占头取得，不在投递时从当前余额或客户端输入猜测。信封保留原事件 ID、范围、时刻及关联 ID。

库存发布器将新确认投往 `${topicPrefix}.fulfillment.results`。履约只接受此受信 Topic 上的 `wms-inventory` 来源，并复用持久化 Inbox、领取代际、有限重试和审计重排。生产需为对应环境 Topic 配置库存生产权限、履约消费权限与传输凭据；本次未部署或授权共享集群。进程启动配置见 `.env.example` 和 `compose.yaml`，两个服务消息开关默认关闭。

旧事件缺少确认版本时保持原 `inventory.events` 投递方式，不据此给履约补造分支。显式声明未知/错误版本的新事件在库存 Outbox 隔离，不能伪装成旧事件被静默忽略。升级应先准备 Topic/ACL、升级履约消费者，再切换库存新生产者；旧未结案 attempt 需要原始可靠事实和审计来源，不以更改事件正文或人工填状态升级。

## 消费屏障

消费时按企业锁定既有 attempt，再核对固定参与仓及原 XID、reservationId、branchId、actionName、routeEpoch。消息不能创建 attempt，也不能补绑未登记的分支。早于原 Try 回执持久化的消息保留待恢复，由有限退避重试；身份不符、未计划的仓、错版本或矛盾终态隔离。

每仓首次确认保存原 allocationId，同一 attempt 的参与仓分配标识必须一致；既有确认不可改写。同一原分支的迟到Try回执只读取绑定，不因超过原截止时刻拒绝这个无副作用重放，也不能把CONFIRMED改回TRIED。查询接口同步显示 `state` 和 `observed_branch_state`，防止后台已确认而界面仍停在 TRIED。

参与仓确认、分配状态、屏障 Outbox 和 Inbox DONE 在同一事务。最终 Inbox 标记失败会回滚本次确认与全部新授权事件；重启后按原消息恢复。重复确认不重复生成授权。只有持久化 TC 来源绑定、TC 提交证据和全部参与仓确认同时具备才进入 ALLOCATED。

每次消息 worker 最多32项，达到20秒后不再领取新项，数据库调用使用既有语句与连接预算；网络接收和本地处理分离。新增履约 Outbox 积压指标和受审计重排适配，保留未发布积压，不能以“当前无发布器”上报零。**本切片尚未启动履约屏障 Outbox 发布器，也未接通出库授权消费者**；完整TM/RM调用和后续出库链继续实施。

## 兼容迁移与证据范围

历史履约 Flyway 只扫描 `db/migration/fulfillment`，原父目录 V009–V011 未进入生产迁移。追加 V014 修复新库及旧手工 Inbox 的表、重排预算和索引；不移动或修改已提交迁移，不用 repair 改写历史。V015追加原分配确认字段和有界 Outbox 投递元数据，旧写入保留兼容默认值。手工改变过迁移历史的数据库须先核对真实历史，不能由本测试证明所有现场可自动升级。

`FulfillmentConfirmationProcessesIT` 使用两个实际服务 Jar、两个 MySQL 和 Kafka：原分支回执未到时等待，最终 Inbox CHECK 失败回滚，履约进程重启后补齐屏障，重复/错分支确认及未知版本分别处理。Try 和 TC 提交证据来自明确夹具；真实 TC 只读适配已另有 `TcAuditRecoveryIT`，两者不能拼称完整真实TM/RM端到端通过。

`FulfillmentInboxMigrationIT` 验证旧 Inbox 内容、UTC微秒和 claim_epoch=7 保留，升级后审计重排不重置代际、积压采样有效、重复启动零迁移。结果记录在交付文档。
