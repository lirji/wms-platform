# 已提交分配的取消补偿

COMP 实现、代表链路及 FINAL 本地门禁均已通过，待 Git 发布及远程 CI。唯一范围来自 [交付计划](../delivery/wms-v1/DELIVERY_PLAN.md)。TC 已提交不改变原 XID、分支和 Fence，不执行 TCC Cancel。

## 数据权威与路径

1. 履约取消与原 attempt 标记同事务。原只读 TC Committed 证明和持久 Try 计划齐备后，逐仓追加 `CommittedCancellationRequestedV1` 到既有履约 Outbox，经 `outbound.authorizations` 发送。原取消决定、操作人、桶、数量及参与仓固定；缺失历史计划不猜测。
2. 出库按原 allocation/attempt 建立或锁定同一出库单，持久化 `outbound_cancellation`。授权和新作业检查该门禁，因此取消先到也能拒绝迟到授权。旧实物事实和库存回执继续保留。
3. 本地恢复每轮处理一单，最多 200 个原桶。按原桶扣除来源库已经受理的 PICK/CANCEL 数量；原实物与库存回执不一致、存在未完成设备身份或缺原桶上下文时，该原行保持处理中，其他已知未执行原行可继续释放。
4. 可证明未执行的余量生成确定性的原 CANCEL 命令。复用来源 T1、库存 T2 和来源 T3，补偿标识与上下文同事务冻结。使用 `outboundSchemaVersion=3`，旧库存消费者必须拒绝而不能静默忽略取消门禁。
5. 库存取消门禁与新 STARTED 共用原单行锁。V3 取消还检查已收到的原全局提交证明、既有 STARTED/UNKNOWN、原预占行和 `inflight_qty`；没有真实未执行证据不释放。CANCEL 只减预占，不减少实物量。
6. 所有原取消库存回执落库后，出库通过既有来源 Outbox 发送 `CommittedCancellationResultV1`，使用 `cancellation.results`。履约仅接收可信出库来源并核对原发送决定，逐仓汇总到 `fulfillment_cancellation_result`。

## 状态和恢复

- `COMPENSATING` / `PROCESSING`：等待原证明、实物结案、库存回执或已有消息恢复。不能向用户宣称已撤销实物效果。
- `COMPLETED`：全部原参与仓的未执行数量释放回执已齐备，且无已拣实物。
- `PARTIALLY_COMPENSATED`：已知未执行部分已释放；已拣部分保留，未扩展退货、回库或业务补偿规则。
- 履约详情增加最近 20 条取消记录，供原 statusUrl 观察；重复取消不换分配尝试或 TCC 身份。
- 出库恢复只检查本库，间隔 5 秒到 300 秒退避；没有远程调用持锁。消息发送、业务失败、隔离和人工重放沿用原有有界 Outbox/Inbox 与恢复审计。未知实物不会因超时被当成未执行。

## 迁移与兼容

新增库存 V049 原单执行门禁、出库 V020 取消进度、履约 V021 逐仓补偿回执。库存门禁必须随仓迁移，防止迁移后旧单重新取得 STARTED。没有新中间件或公开执行接口。

先扩展数据库并更新库存 V3 消费者，再更新出库补偿器和履约生产者；Kafka 预建 `cancellation.results`，写权限仅出库、读权限仅履约。代码回退不能删除已经受理的取消或门禁；历史消息和回执仍需要兼容的恢复执行器。未执行生产部署。

## 验证

- `.local/committed-cancellation-compile.log`：首次编译失败，自动替换误触及旧 applyPick 的参数名；已定位修正，不作为通过证据。
- `.local/committed-cancellation-test-compile.log`：首次测试编译失败，进程夹具误用了不存在的 concat；改为原 argsB，并核对取消 scope 与 Inbox 列名。
- `.local/committed-cancellation-representative-it.log`：首次代表链路失败，出库8项和履约恢复2项通过；多进程在取消库存凭证等待处失败。核对发现MyBatis仅选择NULL列会返回null，已改为同时读取非空原单标识；同时修正迁移必需id。修正后的验证结果见下一条。

- `.local/committed-cancellation-guard-fixed-it.log`：2026-09-13 10:04:16 BUILD SUCCESS；出库8、履约恢复2、真实TC/Kafka多进程1。B最终CANCEL凭证失败保持预占和COMPENSATING；B重启后两仓各一原凭证、两仓结果完成，TCC仍Committed。迁移至C并重启、TC重启重放原回调，10:03:57返回PhaseTwo_Committed。
- `.local/committed-cancellation-final-guards-it.log`：出库10通过；履约测试中新增断言误放入未取消用例而失败，已移到取消用例，不改业务预期。
- `.local/committed-cancellation-guard-completion-it.log`：2026-09-13 10:09:57 BUILD SUCCESS；出库10、履约执行5、两库终态/取消门禁迁移1、仓迁移6、执行许可1全部通过。
- 默认组合验证按模块恢复：TC审计首次遇到本机IDE与Docker映射端口冲突，原运行失败；测试增加有限连接预算。首次履约恢复暴露审计URL参数与生产固定预算冲突，以及未取消流程多余访问取消Mapper，均已修正，生产预算保护未放宽。`.local/backend-remediation-final-tc-audit-fixed.log` 10:27:28 BUILD SUCCESS，审计4项和履约执行5项通过；剩余默认模块随后通过，见下方 FINAL 结果。

已提交后、尚未授权的取消将执行器移交为COMPENSATING，逐仓完成后为COMPENSATED，停止不可能改变结果的TC轮询；晚于原执行器完成的取消继续保留原COMPLETED执行记录，由独立取消状态表达补偿。原attempt冻结首次取消ID，后续新取消操作沿用原补偿决定；同操作键更换原因或操作人拒绝。

### FINAL 本地结果

2026-09-13 默认验证采用已通过前缀、履约修复后定向复验和剩余模块恢复的组合证据；不把最初失败记录改为成功。最后 `.local/backend-remediation-final-verify-tail.log` 于10:58:13 BUILD SUCCESS。当前默认XML合计293用例，失败、错误、跳过均0；134项必需用例检查通过。真实分配/取消/迁移回调在本轮再次通过。

独立 `warehouse-it` 12项、`tc-it` 2项、`failure-it` 3项及故障必需门禁全部通过；报告分别保存于 `.local/reports/{warehouse,tc,failure}`，日志为 `.local/backend-remediation-final-{warehouse,tc,failure}.log`。四服务独立进程smoke、Python 4项测试、Compose静态配置、OpenAPI可重复生成及文档结构检查通过。OpenAPI 99路径113操作；本次未修改前端，前端验证由最终提交CI执行。

最终改动已核对可信消息方向、原决定身份、锁顺序、未知实物保护、原回执完成条件及迁移注释；没有新增依赖或执行生产部署。发布结果以[当前状态](../delivery/wms-v1/DELIVERY_STATUS.md)为准。
