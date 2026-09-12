# 序列出库实施设计与当前PICK切片

本切片继续R13/R14，当前发布版本PICK/SHIP对序列SKU仍拒绝。工作树已实现PICK来源和库存，来源/库存/非空新表迁移及公开查询/实际Kafka进程重启恢复已验证；SHIP未实现，以下设计不能代替未完成链路的证据。普通出库沿用已有原allocation/attempt/orderLine、桶额度和消息T1/T2/T3。不能因增加身份字段改变旧普通消息摘要。

## 身份与持久事实

新增有版本的SerialExecutionSelection，包含1至200个serialId与ownerEpoch；严格整数schemaVersion=1、非负整数epoch、规范化SN、重复SN拒绝、按SN稳定排序、数量等于身份数。epoch必须来自仓内授权身份查询，初始登记当前为1，但协议0合法，不硬编码初值。公开仓内只读查询需复用inventory.read、企业仓范围、当前桶、稳定有界分页，提供实际epoch及是否已拣占用。

来源T1在OutboundPostingService.pick中从原任务/订单/订单行派生完整维度，固定完整选择到SourceCommandContextStore和同事务Outbox。逐身份占用保存原command/task/orderLine/allocation/attempt、SKU/批次/源目标库位、epoch；相同命令必须保持完整选择，不同任务不能重复领取同一活跃身份。来源T3仅原PICK成功回执使对应SN获得已过账可发运额度，数量桶额度与身份进度同事务。后续SHIP必须选择同原订单行、暂存桶、epoch的已拣未发身份。

原身份事实保留历史，不能为重新拣货删除原记录。后续补偿退拣需要释放活跃占用后允许同epoch新任务领取；可使用历史行+只对未释放状态有效的唯一约束（generated nullable active key），不覆盖原command/上下文。状态扩展须支持滚动兼容，补偿尚未实现前保持占用拒绝新任务。

## 库存T2

实际运行入口StockCommandService.applyOutbound，内部InventoryApplicationService.postOutboundReservation验证原已确认预占和订单行、稳定锁定仓路由/库位门/预占/余额。序列选择额外进入新消息摘要，普通消息原摘要不变。数量已迁移后，在同一本地事务验证每个local_serial确为原SKU/批次/源桶、AUTHORIZED/ACTIVE、相同epoch、没有其他活跃拣货归属；记录原PICK身份事实，移动具体身份到目标桶，任何最终写失败回滚全部数量/身份/预占/命令/流水。

local_serial可保留AUTHORIZED表示全球仓归属，已拣归属由独立原事实表表达，不能仅靠目标桶数量推测哪些SN属于哪条订单行。临时选择的源身份不能借用其他allocation/attempt/orderLine或已发身份。旧PICK重放先返回原命令凭证，不把后续已移动的SN拉回。数量盘点涉及任意MISSING且桶有预占/设备领取时，先拒绝登记；未有精确预占身份前不能猜测自由SN。

## 发运与补偿

SHIP不能调用MISSING。需要独立全球SHIPPED终态与原仓/SKU/SN/epoch/发运事实的可靠意图：本地物理出库与意图原子保存，网络事务外完成，历史发运回执重放不能回退当前归属。原PICK/SHIP身份归属和每SN源T3额度都必须核对，部分发运可选择已拣子集。不能把Kafka发送成功、库存回执或全球确认混为同一完成含义。

取消未拣保持既有释放路径。已拣未发取消需要明确退拣实物事实及同SN回库/释放原占用的补偿，已发后取消需要退货/恢复事实，不能只取消TC或回滚镜像。后续TC迁移/全局提交后补偿另循既有授权任务，不以本设计宣称已完成。

## 验证计划

真实MySQL：重复/换SN/换epoch/跨任务/跨订单行拒绝、最后本地身份写失败全回滚、迟到原PICK不回拉、净数量不变身份替换不能绕过占用。实际来源与库存Jar/Kafka/独立库：原选择随T1→T2→T3传播、按SN发运额度、丢回复重启恢复。登记SHIP需实际登记Jar测试并核对原epoch和终态证明；新表必须加入仓迁移并复制实际非空数据。所有测试只用隔离组件，TP99与生产验收不虚构。

## 当前验证

`/tmp/wms-serial-pick-first-it.log`：来源6IT、库存4IT、迁移6IT，全部通过；覆盖原选择、跨任务/订单行、错epoch、原重放、最终身份写失败回滚。`/tmp/wms-serial-pick-process-it.log`已通过18IT，验证公开查询和实际outbound/inventory Jar、Kafka、独立数据库的序列PICK及重启恢复。全球身份授权和TC终态明确来自夹具，不称这一测试验证真实登记服务或TM/RM全链。


### V2 兼容边界与协议校验

仅带 `serialExecution` 的出库消息将内部 `outboundSchemaVersion` 提升为2；普通消息保持1及原摘要。旧消费者明确拒绝2，防止忽略新增身份字段后仍扣普通SKU数量。信封与身份选择版本仍为1。新消费者也拒绝带身份的内部V1，以及普通SKU夹带身份。升级顺序为先库存消费者，再来源生产者，不能向尚未升级的消费者开启序列出库。

`/tmp/wms-serial-pick-final-it.log`正常V2及恢复路径通过，但异常探针错误使用来源未补齐发布元数据的记录，目标错误码断言失败。已改用真实入箱信封的完整payload，`/tmp/wms-serial-pick-probes-it.log` 于06:22:49 BUILD SUCCESS（来源HTTP2及实际进程1），通过两条探针及HTTP数量/身份关系400。此前失败不记为通过。
