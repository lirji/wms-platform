# 序列拣货与分次发运

本切片继续R13/R14，已实现序列PICK及按原SN子集分次SHIP，并验证来源/库存/登记接口、非空迁移及实际Kafka进程重启恢复。普通出库沿用已有原allocation/attempt/orderLine、桶额度和消息T1/T2/T3；普通消息摘要保持原格式。已拣补偿、公开序列调拨、TC迁移仍有独立未完成项，不能将本切片视为整体整改完成。

## 身份与持久事实

新增有版本的SerialExecutionSelection，包含1至200个serialId与ownerEpoch；严格整数schemaVersion=1、非负整数epoch、规范化SN、重复SN拒绝、按SN稳定排序、数量等于身份数。epoch必须来自仓内授权身份查询，初始登记当前为1，但协议0合法，不硬编码初值。公开仓内只读查询需复用inventory.read、企业仓范围、当前桶、稳定有界分页，提供实际epoch及是否已拣占用。

来源T1在OutboundPostingService.pick中从原任务/订单/订单行派生完整维度，固定完整选择到SourceCommandContextStore和同事务Outbox。逐身份占用保存原command/task/orderLine/allocation/attempt、SKU/批次/源目标库位、epoch；相同命令必须保持完整选择，不同任务不能重复领取同一活跃身份。来源T3仅原PICK成功回执使对应SN获得已过账可发运额度，数量桶额度与身份进度同事务。后续SHIP必须选择同原订单行、暂存桶、epoch的已拣未发身份。

原身份事实保留历史，不能为重新拣货删除原记录。后续补偿退拣需要释放活跃占用后允许同epoch新任务领取；可使用历史行+只对未释放状态有效的唯一约束（generated nullable active key），不覆盖原command/上下文。状态扩展须支持滚动兼容，补偿尚未实现前保持占用拒绝新任务。

## 库存T2

实际运行入口StockCommandService.applyOutbound，内部InventoryApplicationService.postOutboundReservation验证原已确认预占和订单行、稳定锁定仓路由/库位门/预占/余额。序列选择额外进入新消息摘要，普通消息原摘要不变。数量已迁移后，在同一本地事务验证每个local_serial确为原SKU/批次/源桶、AUTHORIZED/ACTIVE、相同epoch、没有其他活跃拣货归属；记录原PICK身份事实，移动具体身份到目标桶，任何最终写失败回滚全部数量/身份/预占/命令/流水。

local_serial可保留AUTHORIZED表示全球仓归属，已拣归属由独立原事实表表达，不能仅靠目标桶数量推测哪些SN属于哪条订单行。临时选择的源身份不能借用其他allocation/attempt/orderLine或已发身份。旧PICK重放先返回原命令凭证，不把后续已移动的SN拉回。数量盘点涉及任意MISSING且桶有预占/设备领取时，先拒绝登记；未有精确预占身份前不能猜测自由SN。

## 发运与补偿

SHIP使用独立全球SHIPPED终态。来源V018保存原shipment_command_id与逐SN过账时间；库存V042的serial_shipment_intent与物理扣量、local_serial离库同事务提交；登记V005保存原仓/SKU/SN/epoch/发运引用的不可变证明。来源按原PICK、订单行、暂存桶和epoch检查已过账未发身份，部分发运只消费所选子集。公开shippable-serials查询需outbound.read、企业/仓范围及原订单行/库位/批次，稳定有界分页；查询本身不占用身份。

恢复器在网络事务外调用登记shipments接口；每轮20项/10秒、单次HTTP1500ms、15秒领取租约和最多12次自动尝试。原证明必须完整匹配，历史成功重放不回退当前归属；失去领取代际的执行器不能写回。失败保留意图并退避或隔离，人工重排与审计同事务。serial-recoveries中的SHIPMENT反映全球确认；来源库存POSTED仅证明本地过账，不能代替全球完成。详见[登记运行配置](SERIAL_REGISTRY_RUNTIME.md)。

取消未拣保持既有释放路径。已拣未发取消需要明确退拣实物事实及同SN回库/释放原占用的补偿，已发后取消需要退货/恢复事实，不能只取消TC或回滚镜像。后续TC迁移/全局提交后补偿另循既有授权任务，不以本设计宣称已完成。

## 验证计划

真实MySQL：重复/换SN/换epoch/跨任务/跨订单行拒绝、最后本地身份写失败全回滚、迟到原PICK不回拉、净数量不变身份替换不能绕过占用。实际来源与库存Jar/Kafka/独立库：原选择随T1→T2→T3传播、按SN发运额度、丢回复重启恢复。登记SHIP需实际登记Jar测试并核对原epoch和终态证明；新表必须加入仓迁移并复制实际非空数据。所有测试只用隔离组件，TP99与生产验收不虚构。

## 当前验证

2026-09-13 SHIP验证：`.local/serial-shipment-second-it.log`来源7、库存4、登记HTTP3全部通过；端口故障夹具验证最终写回滚、错误证明、审计与旧代际隔离。随后严格整数epoch、最小历史证明及进程测试补充后，`.local/serial-shipment-process-it.log`于07:21:44 BUILD SUCCESS（6分40秒）：来源HTTP2、登记HTTP3、非空双库迁移6、实际进程1。实际outbound/inventory/registry JAR、MySQL 8.4.11、Kafka 3.8.0和XXL执行器验证分批扣账失败重启、全球登记已提交但网关回执丢失、再次重启恢复，最终数量/身份/原证明均只记一次。XXL admin回调、原收货和TCC授权是明确测试夹具；不冒充生产或完整TC验收。

OpenAPI新增到91路径；生成器多行参数缩进缺陷已修复，OpenApiContractTest 5项通过。上线应先登记服务，再库存消费者及调用凭据/XXL配置，最后来源生产者。尚未确认全球回执时保留SHIPMENT恢复意图；应用回退不能撤销已发实物或忽略V2身份消息。生产部署未执行。

`/tmp/wms-serial-pick-first-it.log`：来源6IT、库存4IT、迁移6IT，全部通过；覆盖原选择、跨任务/订单行、错epoch、原重放、最终身份写失败回滚。`/tmp/wms-serial-pick-process-it.log`已通过18IT，验证公开查询和实际outbound/inventory Jar、Kafka、独立数据库的序列PICK及重启恢复。全球身份授权和TC终态明确来自夹具，不称这一测试验证真实登记服务或TM/RM全链。


### V2 兼容边界与协议校验

仅带 `serialExecution` 的出库消息将内部 `outboundSchemaVersion` 提升为2；普通消息保持1及原摘要。旧消费者明确拒绝2，防止忽略新增身份字段后仍扣普通SKU数量。信封与身份选择版本仍为1。新消费者也拒绝带身份的内部V1，以及普通SKU夹带身份。升级顺序为先库存消费者，再来源生产者，不能向尚未升级的消费者开启序列出库。

`/tmp/wms-serial-pick-final-it.log`正常V2及恢复路径通过，但异常探针错误使用来源未补齐发布元数据的记录，目标错误码断言失败。已改用真实入箱信封的完整payload，`/tmp/wms-serial-pick-probes-it.log` 于06:22:49 BUILD SUCCESS（来源HTTP2及实际进程1），通过两条探针及HTTP数量/身份关系400。此前失败不记为通过。
