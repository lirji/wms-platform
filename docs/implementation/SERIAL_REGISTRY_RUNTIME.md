# 序列号登记调用与恢复

登记服务是企业/SKU/规范化序列号的归属权威；库存的 `local_serial` 和 HOLD 余额是本地事实。全局 ACTIVE 不代表质量合格，库存仍须质量、归属代际及本地状态全部满足才能执行。当前本地序列号长度上限64，登记服务支持128；本地入口明确拒绝超长，不截断。保留现有企业/仓/序列号的更严格本地唯一约束，重放核对SKU、批次和库存桶，不跨SKU复用。

## 调用配置

- `wms.serial.client.enabled=true` 开启库存调用器；默认未开启。
- `wms.serial.client.base-url` 是登记服务根地址，无路径、查询或用户信息。
- `wms.serial.client.token-directory` 指向外部凭据控制器挂载的受控目录。文件名为企业ID UTF-8 的 SHA-256 小写十六进制加 `.jwt`，内容为该企业的服务JWT；不得放入Git。读取上限16KiB，不跟随文件符号链接，轮换使用同目录原子替换。
- 默认HTTPS；受控隔离网络采用HTTP时显式设置 `wms.serial.client.allow-http=true`。生产网络、ACL和凭据颁发由部署管理，不在应用内自行签发JWT。
- JWT必须使用登记服务配置的issuer/audience、`allowed-subjects`内的稳定主体、企业和仓范围，以及 `serial.registry.write`/`serial.registry.read`；不要使用用户JWT。令牌轮转保留同一服务subject，否则命令审计会拒绝换主体重放。

连接超时500ms，整个调用1500ms，无隐式重试、不跟随重定向，含chunked正文的响应累计上限64KiB。单实例最多8个在途请求，每企业2个；单实例32次/秒、每企业8次/秒。4个HTTP工作线程、队列64，拒绝时保留HOLD。扩容必须合计所有实例对登记服务的预算；这些是配置上限，不是已验证生产容量承诺。

## 持久化恢复边界

`serial_recovery_intent` 与HOLD事实在本地事务提交，固定原操作、库存桶摘要、SKU、转移引用和from_epoch；历史缺原始上下文不自动推测补齐。`serialTransferRecovery` 的XXL参数为 `enterpriseId,warehouseId`，使用已配置的真实调用器，每次最多20条/20秒，每条独立事务。任务也恢复首次收货认领，不仅转移。

领取持久化claim_epoch和15秒租约；网络请求在库存事务之外。远端成功但丢回执、或库存最终提交失败时，下次仍以原动作幂等键重放。放行同时检查本地version、SKU、原操作、转移和领取代际；本地状态已变不能被旧执行器覆盖。正常重试带指数退避和抖动，上限12次，终止为ISOLATED；业务冲突除暂时状态/版本竞争外直接隔离，库存不自动清除。没有配置调用器时任务失败，不能空跑报告成功。

查询入口为 `GET /api/wms/v1/warehouses/{warehouseId}/serial-recoveries`，需要 `messaging.read`；按原意图 `/{intentId}/retries` 重排需要 `messaging.recover`、Idempotency-Key、expectedEpoch和reason。审计与重排同事务，递增claim_epoch而非重置；同键只返回原受理，不重复重排。

当前接线分阶段实施：登记服务HTTP、恢复执行器和受审计隔离重排具备实际实现；消息侧序列号观察、转移来源释放的可靠传播仍需下一切片接通，不能仅启动执行器就宣布序列号全链路完成。既有同步领域方法保留给既有用例验证，运行消息路径必须先提交意图再异步协调。生产部署及旧数据处理未执行。

盘点同步领域用例仍不能直接接有界HTTP循环：一行多个序列号会在限流时整体回滚本地进度，重复从头调用导致无法推进。需要逐序列号持久化登记进度后接通；当前未开启这种不完整接线。

兼容旧首次激活记录：新节点写入receipt_operation_id=原claim_operation_id。旧ACTIVE缺该字段时，只允许同仓、同原认领操作且transfer_id为空的写入重放补齐；已转移身份不能借旧认领操作补齐或取得收货成功。

恢复领取、授权落库和人工重排持有短事务仓路由共享锁；迁移QUIESCING/RETIRED仓拒绝写入，网络调用期间不持有该锁。

## 分次发运确认

`POST /internal/wms/v1/serial-identities/shipments`只接受受信服务主体、企业/仓范围、`serial.registry.write`和原发运事实；expectedEpoch必须是非负整数。返回独立shipment证明（schemaVersion=1、企业/仓/SKU/SN/epoch/shipmentRef），历史重放保持该证明，不用当前身份重建历史。SHIPPED不能被旧收货认领或激活恢复为ACTIVE。

库存V042持久化serial_shipment_intent，`serialTransferRecovery`先恢复发运，再执行原释放/收货恢复，各阶段独立有界。发运阶段每轮20项/10秒、15秒领取租约、12次自动尝试；远程调用沿用上述配置与配额，不占库存事务。SHIPMENT查询与审计重排沿用serial-recoveries入口；失败不能再次扣库存。登记成功和本地最终写入之间的失败通过原证明重放恢复。升级顺序为登记服务→库存调用器/消费者→来源。

2026-09-13实际三服务JAR、Kafka、MySQL与XXL执行器验证已通过，见[序列出库证据](SERIAL_OUTBOUND_DESIGN.md)。XXL admin是协议夹具，生产配置和容量尚未验收。
