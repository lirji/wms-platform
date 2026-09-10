# v0.4 跨仓预占 Seata TCC 设计

## 1. 已批准的事务选型

用户明确批准：跨仓预占采用Seata TCC；其余链路使用本地事务与可靠消息。该决定替换原自研PREPARED/COMMIT/ABORT协调方案，不改变首版独立inbound/outbound/inventory的边界。本次仅修改设计文档，未安装Seata、执行DDL或编写业务代码。

| 范围 | 机制 | 完成含义 |
| --- | --- | --- |
| inventory单仓库存更新 | 本地事务 | 余额、流水、预占/claim、凭证、Outbox同一物理事务 |
| 入/出库与库存过账 | 本地事务+Outbox/inbox+状态机与补偿 | 来源事实、库存凭证和回执最终收敛 |
| 一次跨仓预占attempt | Seata TCC | 各仓库存资源全部确认或全部取消，二阶段过程中可能暂时不同步 |
| 拣货、发运、设备、调拨运输、退货 | 持久化业务协议 | 不放进长时间Seata全局事务，不让Confirm执行设备动作 |

不启用Seata AT或XA模式来包裹上述链路。ShardingSphere仍使用LOCAL事务处理分片内更新，Seata TCC在业务接口层协调；不叠加AT数据源自动代理或第二套XA协调器。

## 2. TM、TC、RM与权威

- `wms-fulfillment`为TM发起方：持有业务订单/attempt、选仓清单和业务幂等，调用TCC Try并请求TC提交或回滚。
- Seata Server为TC：持久化XID、分支状态和全局提交/回滚进度，驱动Confirm/Cancel及恢复重试。TC是全局事务状态权威。
- 各Cell的`wms-inventory`为RM：实现仓级ReservationTccAction的Try/Confirm/Cancel，管理真实库存资源。
- fulfillment保留观察到的TC状态、XID和分支结果，用于业务恢复；不再自建可以覆盖TC的COMMIT/ABORT决策表，也不通过Kafka自发二阶段决定。

一个attempt只绑定一个XID，每仓一个有界分支；重分配必须先确认旧attempt取消完成，再创建新attempt/XID。参与者集合、仓级行数量、策略版本及摘要在Try前固定，不能在同一XID内静默换仓。并行Try必须显式传播/清理XID，S0未验证前顺序调用有界参与者，避免线程上下文丢失。

## 3. 三阶段业务语义

| 阶段 | 本地事务内容 | 失败与重复 |
| --- | --- | --- |
| Try | 校验企业/仓/单位/批次/效期/门禁、固定库存桶/serial、检查可用量；创建TRIED预占并增加reserved；写必要流水、fence及Outbox | 数量不足抛明确失败使全局回滚；本地失败全部回滚；同业务attempt异内容拒绝 |
| Confirm | 按XID/branchId/actionName/仓/attempt读取原TRIED记录，转CONFIRMED；占用量不再增加，写确认事件与fence状态 | 幂等重入；依赖短暂不可用返回失败供TC恢复，不虚报成功 |
| Cancel | 释放本分支仍TRIED的reserved并转CANCELLED，写释放流水/事件与fence状态 | 空回滚安全；已取消幂等；不能释放其他attempt或已消费库存 |

Try成功后本地数据库事务已提交，不跨网络持续持有行锁；业务库存仍被占用。TRIED不由XXL-JOB按expires_at释放，全局超时交由TC决定回滚。Confirm不能因Try后效期变化/门禁冻结等正常业务变化反向做一次Try；其任务是完成已预留资源的确认。质量/效期变化仍可阻止后续执行授权，进入业务异常/重分配，而不是让Confirm永久业务失败。

TCC成功指资源预占确认，不扣实物on_hand、不标记已经出库、不派发设备。后续发运由原本地库存事务处理。CONFIRMED后的用户取消是新业务取消/释放流程，不再调用这个已提交全局事务的Cancel。

## 4. 状态与执行授权屏障

仓级预占：`TRIED -> CONFIRMED -> CONSUMED`；或 `TRIED -> CANCELLED`。全局提交后，未执行的剩余预占可经独立业务释放转RELEASED；部分消费/释放以明细数量累计，全部结清才进入终态，不能对已消费或STARTED未核验数量重复释放。非TCC旧HELD/EXPIRED接口不再用于跨仓路径。TRIED及CONFIRMED均计入reserved；全局处于回滚重试/提交重试时不得TTL清理。

业务attempt：`PLANNED -> TCC_STARTING -> TCC_TRYING -> TCC_COMPLETING -> ALLOCATED`；回滚路径`TCC_COMPLETING -> ROLLBACK_PENDING -> CANCELLED/FAILED`；无法确认结果为`RECOVERY_PENDING`。TC的精确枚举通过版本化适配映射，不复制自定义全局决策。

流程：

1. 在普通本地事务创建业务attempt，固定参与者和幂等身份，状态TCC_STARTING。
2. TM通过选定Seata SDK/代理开启全局事务，取得XID；首次Try前将attempt↔XID以短本地事务持久化。此元数据不受AT自动回滚影响。
3. 调用各仓Try，任何一仓失败由TM报告并请求全局回滚；不要捕获错误后返回成功触发错误提交。
4. TM正常完成后由TC驱动Confirm；取消/异常/超时由TC按自身状态机驱动Cancel。业务不得自行并发发送相反二阶段操作。
5. 业务成功屏障同时要求：TC已确认全局提交完成的可靠证据，以及固定参与者所有仓均CONFIRMED。不能把全局方法返回或单个Confirm事件当作屏障已满足。
6. fulfillment在普通本地事务将attempt设ALLOCATED并写创建outbound单/执行授权的Outbox；与TCC事务解耦，宕机后可补齐。

TC终态证据由受控SDK/状态查询及审计适配取得，实际API在S0验证，不能随意拼写不存在的TC REST端点。持久化已观察终态后才允许按保留策略归档。TC记录查不到不等于已提交或已回滚，进入RECOVERY_PENDING并核查终态审计/分支证据，不立即新建XID或放行出库。

TC完成前可能有仓A已CONFIRMED、仓B仍TRIED；A仍不能收到执行授权。此屏障保证不会在全局尚未确认时开始仓内实物执行，不承诺各库在同一时刻可见相同状态。

## 5. 幂等、空回滚、防悬挂与分片

使用所选版本的TCC Fence能力，计划启用`useTCCFence`；`tcc_fence_log`和业务预占/余额必须参与同一物理本地事务。Fence负责框架阶段防重/空回滚/悬挂控制，业务仍须校验企业/仓、attempt、数量摘要和原资源，不能只依赖XID防重复订单。

每个分支记录XID、branchId、actionName、warehouseId、routeEpoch、allocationId、attemptId、requestDigest、reservationId。二阶段通过持久化BusinessActionContext/分支定位记录恢复仓和数据源，不依赖原HTTP线程、ThreadLocal或本机内存。全局XID格式长度以选定Seata官方schema为准，不沿用业务ID的VARCHAR(64)。

ShardingSphere的关键门禁是：Fence开启本地事务之前已选择正确warehouse物理数据源，Fence SQL与余额/预占SQL最终落同一连接；不能Fence在默认库、库存在业务分片。标准Fence实现若无法满足多数据源路由，S0需验证每RM绑定单一库存物理库的部署方案或受支持的数据源适配；无法证明原子性时阻塞该组合，不绕过Fence后宣称完成。

Confirm/Cancel只能由经过身份校验的RM框架回调路径进入；XID不是认证凭据，普通调用者不能手填XID完成确认。保留框架Fence终态与业务去重墓碑至恢复/重放窗口结束，未完成事务绝不清理。

官方提供TCC接口与Fence机制说明，本项目仍需验证具体版本、Spring事务代理顺序、分片路由及故障恢复。[TCC模式](https://seata.apache.org/docs/v2.1/user/mode/tcc/)、[TCC Fence](https://seata.apache.org/blog/seata-tcc-fence/)

## 6. Outbox与事务上下文隔离

Try的Outbox可以发布占用变化/分支TRIED事实，但消费者必须识别其暂态，不能驱动拣发。Confirm事件只是分支已确认，不代替全局成功证据；Cancel事件释放投影占用。fulfillment只在成功屏障后发业务AllocationCompleted/执行授权。

TCC之外的收货/发运命令、结果、设备调用、投影与任务保持本地事务+Outbox；不把RootContext/XID从线程池泄漏到这些链路，也不通过Kafka把旧XID当作继续加入原全局事务的依据。xid可作为审计字段，消费时不自动bind成为事务上下文。

## 7. 恢复、取消与运维

TC负责全局事务恢复重试；XXL-JOB只查询/告警/补齐业务投影和完成事件，不直接调用Confirm/Cancel、不改TC表、不按本地时钟释放TRIED。TM或RM重启后根据持久化映射重新定位，保持原XID/branchId。

用户在TCC进行中取消：fulfillment持久化cancelRequested，由受控事务管理入口按TC当前状态申请回滚；若提交已不可撤销则等待成功屏障，再走已确认资源的业务取消。TC不可达时保持RECOVERY_PENDING，不能用本地cancelRequested作为释放决定。已进入物理STARTED的动作仍按08恢复，不由TCC取消。

TC自身部署高可用与持久化会话存储，服务端/客户端版本、事务分组映射、注册配置、TLS/认证、超时和二阶段重试/告警由S0锁定。不得假定dev-infra已有Seata；当前仅规划新增共享基础设施资源，实施前登记，不修改其他项目。

恢复演练包含TC会话/Fence/预占/业务attempt/Outbox的对应关系。只恢复业务库而丢TC决策会产生未知占用；不能凭“库已起来”放行。TRIED持有时间、TC提交/回滚重试、分支失败、Fence冲突和业务完成屏障延迟需要独立监控。

## 8. 验收与版本锁

S0新增Seata Server/Client精确版本及Java21/Boot/MyBatis/ShardingSphere/Fence组合POC；不在本次凭文档指定未经验证的生产补丁版。S4将自研协调器任务替换为Seata TCC接入。现有AC-10..12细化，新增AC-41..44：空回滚/悬挂/重复、TC故障二阶段恢复、Fence同片事务、终态缺证据及上下文隔离。

故障测试覆盖Try提交后响应丢失、Cancel先于Try、Confirm成功后响应丢失、TC提交决策后宕机、某仓二阶段长时间不可用、TC记录归档后业务回执未齐、同attempt重复开启事务、线程池XID泄漏。真正断连/宕机仅在本任务隔离环境执行。

## 9. v0.4重复Try和启动恢复补充

[幂等专项第2、3节](10-idempotency-protocols.md)补齐本文件规则：同业务键不同XID/branchId不作为重复成功，禁止资源改绑；非所有者Cancel不得释放原资源；Try失联禁止盲目重注册分支。attempt先领取launchEpoch再begin，绑定XID以数据库CAS确认后才能Try；已绑定XID永不覆盖，无绑定且证明无业务分支的空启动单独清理。新增AC-45/46，不替代原Fence同物理事务和空回滚验收。

## S0实测补充（候选，不改变正式门禁）

Seata2.6.0的Finished无法区分会话清理前提交/回滚。已建立[TC终态审计候选](../implementation/TC_TERMINAL_EVIDENCE.md)，验证DB审计故障恢复与单RM双仓回调、TC部分确认后重启。审计未与正式attempt/分支屏障及业务Outbox结合，独立RM/分片组合仍待验收；不将该实验迁移直接部署到生产TC。
