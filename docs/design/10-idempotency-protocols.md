# v0.4 幂等协议与故障验收补充

## 1. 范围与身份层次

本次按用户批准补齐四项协议，不改变Seata TCC选型，也不改变同键异参拒绝、Inbox与业务同事务、HTTP/MQ共用处理入口、一次permit一次结案和累计逆向数量约束。本文是09与08的补充；涉及旧字段冲突时，以本次明确的业务效果与尝试关系为准。以下均为待实现协议，不代表已通过组件测试。

| 身份 | 含义与作用域 | 重试规则 |
| --- | --- | --- |
| clientOperationId | 企业+来源+动作+仓内的一次调用 | 原请求重发沿用；换键不能绕过业务效果约束 |
| businessEffectKey / effectId | 企业+仓+来源服务+动作内的一次有意业务效果 | 跨HTTP/MQ、设备重报、授权尝试保持稳定；由拥有者持久化事实/动作槽生成 |
| executionAttemptId / commandId / permitId | 为同一效果进行的一次授权与执行尝试 | 同次重试不换ID；安全结案后才可申请下一尝试；一个command最多一个最终结案 |
| allocationAttemptId | 一组固定跨仓参与者的预占尝试，现有attemptId | 与上述仓内执行尝试不同；一个attempt最多绑定一个XID |
| XID+branchId+actionName | 一次TCC分支的所有者身份 | 不代替业务效果或预占的业务唯一键 |

所有查询先校验企业、仓及来源权限，再重放历史结果。digest按版本化规范编码业务字段，固定数量/单位与换算版本、目标身份、集合排序规则和空值规则；保存原规范化请求及digestVersion，重试用原版本比较，不受新部署默认值变化影响。不同授权尝试可有不同意图摘要，同一尝试不能改摘要；最终实物结案仍单独使用settlementDigest。

## 2. TCC重复Try与资源所有权

仓级预占业务键B=`enterpriseId/warehouseId/allocationId/allocationAttemptId`；所有者O=`xid/branchId/actionName`。Try在Fence包裹的同一个物理事务内竞争B的唯一记录，绑定O并保留原请求摘要。不得因唯一键冲突直接当作成功，不允许更新O接管预占。

| 重复请求 | 处理规则 |
| --- | --- |
| B、O、规范化请求相同，仍在合法Try阶段 | 返回原reservationId与原结果，不再增加reserved或重复写业务流水 |
| B、O相同，请求不同 | 拒绝TCC_CONTEXT_MISMATCH；不覆盖摘要或资源 |
| B相同、O不同，即使请求相同 | 拒绝TCC_OWNER_CONFLICT；新分支不能冒充已取得资源，不直接复用旧成功结果 |
| 原分支已取消、提交或事务结果未知 | 通过状态查询恢复；不重新执行业务Try，不将已取消响应解释为预占成功 |

框架Fence可能在业务回调前拦截重复调用；所选版本下的返回/异常适配必须在S0验证，不能假设注解会返回原reservationId。原业务结果保存在本地，查询用于恢复，不将查询伪装成一次成功的新Try。

Confirm只处理O完全匹配的资源。Cancel只释放O完全匹配且仍TRIED的资源。不同所有者的失败分支按无业务效果清理自己的Fence/分支状态，绝不能取消B所指的其他分支；这要求有可核对的冲突/无资源证据。若自己的Fence显示Try成功却缺少对应资源，属于数据不一致，告警并等待修复，不能冒充空回滚返回成功。正常Cancel先到仍由Fence建立防悬挂记录。

首次Try响应丢失后，TM不得配置应用层或RPC层盲目重试该TCC action。先查询原attempt的占用及TC状态；所选SDK若不能可靠恢复原分支结果，则将当前全局事务置于受控回滚/恢复流程，等回滚确定后用新allocationAttempt重做。TC的合法Confirm/Cancel重试保留。必须用实际代理/RPC栈验证重复Try是否重新注册branchId，禁止凭注解名推断。

## 3. allocationAttempt绑定XID与启动崩溃

fulfillment_order行通过CAS选择active_attempt_id；同一业务分配不能由两个不同attempt同时占用。旧attempt未知或回滚未完成时不能切换；已提交后走业务释放并满足原有执行安全条件，才能重分配。

启动流程不在网络调用期间持有数据库事务：

1. 本地事务创建固定参与者attempt。执行器以state、version、launchEpoch、xid IS NULL做CAS领取启动权，持久化launchOwner/leaseUntil；必须检查影响行数。
2. 领取者调用TC begin，取得XID。通过另一个本地事务CAS绑定：匹配launchEpoch/owner、xid IS NULL和可绑定状态；同时记录xidBoundAt、进入TCC_TRYING。绑定后XID不可覆盖。
3. 只有确认绑定成功的执行器可以发首次Try，每次发起前校验绑定XID/启动代际。绑定事务响应丢失时先读同一attempt确定结果，禁止猜测未提交而再begin。
4. CAS失败者不得Try或提交自己的空事务；对已知自有未绑定XID申请受控回滚，失败则记录清理任务并等待TC超时恢复。不得回滚数据库中已经绑定的其他执行器XID。
5. begin成功但响应丢失，或begin后绑定前宕机，标记LAUNCH_UNKNOWN。恢复者先锁定attempt：如果已绑定，恢复原XID；如果仍未绑定，原子提升launchEpoch使旧执行器永久失去绑定权。只有确认所有Try入口都受“绑定成功后才能发起”约束，才能判定旧启动没有业务分支、开启新启动代际。不可证明时保持RECOVERY_PENDING。已知空XID主动清理，未知空XID由TC超时回收；保留启动审计，不能靠租约超时直接推断已绑定事务失败。

CAS与恢复提升epoch竞争同一行，因此原执行器要么先绑定，恢复者只能接管原XID；要么先被隔离，旧绑定失败且禁止Try。这里允许清理/替换的是从未绑定且证明没有业务分支的空启动，不是对一个已绑定attempt更换XID。绑定后的恢复以查询TC及分支、补齐业务状态为主，不重新遍历Try。若尚未Try或只完成部分仓，未证明固定参与者全部Try成功就不得正常结束全局方法触发提交；TC仍允许回滚时申请原XID回滚，若已进入不可撤销提交则异常恢复并告警，禁止放行或替换attempt。

## 4. 按动作定义稳定业务效果身份

来源服务事前创建持久化动作槽并返回effectId，客户端重新登录/超时后从任务恢复入口取得同一动作槽，不直接通过随机clientOperationId制造新实物事实。外部事实号必须受来源身份校验并有唯一映射；无可恢复身份的现场重复报数进入核验，不能仅凭数量/时间相同自动合并或按新请求直接累计。

| 动作 | 稳定业务事实/动作槽 | 合法拆分与新效果 |
| --- | --- | --- |
| 收货 | receiptSessionId + handlingUnitId/受控receiptPartId + 入库行 + RECEIVE | 同一托盘/容器/分批登记重复提交复用身份；新到货批次事前创建新receiptPart，锁入库行校验剩余额度；普通无标签货通过服务端登记分批身份，不以订单行独占所有收货 |
| 上架/拣货/移库 | taskId + 服务端subActionId + 行 + 动作 | subAction事前分配有界数量；重试复用，补拣为剩余量建立新subAction并引用原任务，不能重复领取已结算数量 |
| 发运 | shipmentBatchId + shipmentPartId + 行 + SHIP | manifestRevision属于授权尝试摘要；未执行前改包裹不换效果身份；合法分批发运先创建新shipmentPart并校验父行剩余额度 |
| 退货/业务补偿 | returnCaseId或compensationCaseId + caseLine/partId + originalPostingLineId + 动作 | 同一退回/补偿事实复用casePart，不能因任务重跑新建case；独立部分退回建立新part，并在原posting行锁下限制累计可逆量 |
| 盘点调整/对账修复 | approvedAdjustmentId/remediationCaseId + 行 + 动作 | 审批后内容冻结，重跑同一效果；修改数量需新修正用例及审批，不覆盖已入账结果 |

业务键用上述完整字段建立权威唯一映射并分配不透明effectId，不靠字符串拼接或哈希碰撞假设证明业务唯一。来源系统的离线device/session/sequence与effectId另建唯一映射，设备重放恢复原动作槽；重置会话不能自动创造同一事实的新身份。父行数量预算与新子动作登记在来源本地事务中仲裁，库存侧仍按既有permit/claim保护真实资源。

## 5. 终态失败后的合法新授权尝试

业务效果与执行尝试分开持久化。source_command和stock_command不再以effect+action永久只允许一条命令；增加executionAttemptId及attemptNo。同一效果可有多个历史失败尝试，但只允许一个活动尝试和一个有效过账，不能仅删除旧唯一键而不增加效果权威行。

来源source_effect与inventory.stock_effect分别拥有本服务的权威状态，通过协议协作，不跨库事务。effect保存activeCommandId、appliedCommandId、version；每次命令保存effectId、executionAttemptId、attemptNo、previousCommandId及不可变意图。APPLIED的效果不得重新授权，后续退货/修正是引用原posting的新业务效果。原命令REJECTED/CANCELLED结果永久保持，重试原command仍返回原结果。

安全重新授权顺序：

1. 来源锁定effect/任务，持久化停止旧尝试派发的状态、取消原因和未执行证据。若旧动作已STARTED、UNKNOWN、已有实物事实或库存结果未知，停止创建新尝试，进入核验/恢复；不能因REJECTED字样直接重做实物。
2. 请求inventory安全取消旧命令/permit。库存与start/posting竞争同一effect及命令/permit记录：确定未执行且未APPLIED时，保存安全关闭证据并释放旧claim；不存在/尚可取消的旧命令按原协议写CANCELLED墓碑，已REJECTED的命令保持原结果，安全关闭事实另存，绝不改写成CANCELLED；否则返回原成功或未知状态。
3. 来源收到或查询到安全关闭凭证后，本地事务CAS将同一effect推进下一attemptNo，固定新commandId与摘要，写Outbox；并发请求只能有一个胜者，失败者读当前尝试。普通失败修正也走此受控入口，不能通过改clientOperationId绕过。
4. inventory接受新命令时，锁stock_effect，核验previousCommandId对应本地安全关闭事实、attemptNo恰为下一次、尚无appliedCommandId；原子切换activeCommandId并建立新permit/claim。未核验前不得授权实物。新命令早到则等待依赖/返回处理中，不能跳过旧尝试。
5. 晚到旧命令、旧STARTED请求和旧结果均携带旧commandId/attemptNo，命中原终态或被活动尝试校验拒绝，不能覆盖新尝试。设备可信晚到实物事实进入异常核验，不能以旧代际直接丢弃，也不能自动当新尝试过账。

库存APPLIED事务同时CAS设置stock_effect.appliedCommandId、写posting/ledger/Outbox并结案permit；唯一约束保证同一效果至多一份有效posting。来源回执同步同事务校验effect/attempt/command后更新，旧尝试回执只更新自己的历史，不能倒退activeCommandId。取消与重授权接口均有独立请求幂等键。

## 6. 故障验收明细

以下对应唯一实施计划AC-45..AC-50，当前全部planned。真实TC/RM/两库存分片/MySQL测试在获授权的隔离环境实施；设备模拟结果与真实硬件验收分开。

| AC | 故障安排 | 必须观察的结果 |
| --- | --- | --- |
| AC-45 | Try本地提交后丢响应；按实际RPC栈分别重复原身份、制造新branchId与新XID；交错Confirm/Cancel | reserved最多增加一次；原所有者不变；非所有者无权释放；异内容拒绝；原事务最终提交或回滚可恢复，无错误成功分支 |
| AC-46 | 两TM同时启动同一订单/attempt；在begin前后、绑定提交前后、仅部分仓Try完成时断连/宕机；暂停旧执行器至epoch被替换后恢复 | 唯一活动attempt及绑定XID；只有CAS胜者可Try；绑定响应未知先查询；无分支空启动可清理，已绑定XID不替换；旧代际无权绑定 |
| AC-47 | 同次收货换client键经HTTP/MQ重报，设备离线重复；再提交合法第二批及补拣子动作 | 同一事实仅一效果/一次入账，真实不同批次正常累计；父额度不超量；无事实身份的歧义请求隔离而非自动入账 |
| AC-48 | 未STARTED包裹修改/业务拒绝后，两请求并发重授权；穿插旧Try/start/命令/回执与取消结果丢失 | 一个活动executionAttempt；旧结果不被覆盖；安全关闭后新摘要合法；旧command不能再执行，最终同effect仅一posting |
| AC-49 | 在STARTED、UNKNOWN、已记录实物事实、APPLIED各状态申请重授权；再重放原请求 | 不产生新实物尝试；恢复原结果或核验；APPLIED效果只能新补偿用例，不重复扣/加库存 |
| AC-50 | 同一补偿casePart换请求键重复、任务重跑；不同部分逆向并发，原posting迟到；滚动版本重放原摘要 | 同一补偿效果一次入账；独立部分合法累计且不超原可逆量；原事实未到DEFERRED；旧digestVersion比较一致，异参始终拒绝 |

每项同时核对业务效果行、尝试、TC/Fence（适用时）、余额、claim、不可变流水、posting、Outbox/Inbox和来源累计量；记录注入点、最终状态、恢复耗时和未完成原因。不能只检查HTTP结果或唯一索引存在。保留原AC-04/05/13/21/22/34/35回归，证明本次补充没有破坏原本地事务与消息协议。
