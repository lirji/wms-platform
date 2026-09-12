# 原生库存RM与TM适配器

原生RM切片0a1ec85已通过真实TC、两个库存Jar及数据库回归，已正常发布远程main；上一授权切片bd6的main和分支CI均通过。履约自动执行器的后续实现和实际进程证据见[持久化自动分配](FULFILLMENT_EXECUTION.md)；这里不代表完整R14验收。

## 原始登记与恢复

内部入口为`POST /internal/wms/v1/warehouses/{warehouseId}/tcc/tries`，必须使用`wms-fulfillment`服务JWT，校验企业、仓范围及`inventory.tcc.try`权限。`TX_XID`必须属于明确配置的TC通告地址；`wms.tcc.xid-addresses`可与NAT后的`wms.tcc.servers`不同，默认沿用连接地址。SDK实际只连接配置的注册列表，不把XID地址直接当连接目标。只提供Try，Confirm/Cancel由TC原生回调触发。

[V1 JSON Schema](../../wms-contract/src/main/resources/contracts/warehouse-tcc-try-v1.schema.json)及Java契约携带明确货主、allocation/attempt、cell/routeEpoch及最多200行原订单行/源库位/SKU/批次/数量/基本单位/效期条件。规范化后服务端计算摘要；重放必须与原XID、完整请求和资源身份一致。RM复核商品状态、单位、精度、批次归属、效期、仓路由及库存可分配量。

V036追加`inventory_tcc_intent`。先提交REGISTERING意图，随后在业务事务外登记TC分支。只有本次成功创建意图的调用方可登记一次。登记结果未知保留原XID；租约过期或进程重启都不能推断没有分支并再次登记。已知branch可按原身份恢复Fence Try。库存预占、流水、Outbox、意图TRIED与官方Fence使用相同数据源和Spring物理事务，最后任一步失败整体回滚。

TC回调同样锁定原企业/仓路由、核对cell/代际/意图/XID/branch/资源/摘要。校验在官方Fence之前且同事务，包括不调用业务方法的空回滚。登记应答丢失时，匹配原意图的真实TC回调可绑定原branch并完成空回滚；迟到Try被原Fence及CANCELLED意图拒绝。默认不清理Fence，不能伪造统一保留期。

## 运行配置和边界

默认不启用RM。启用需要`wms.tcc.rm.enabled=true`、`wms.tcc.rm.cell-id`、`wms.tcc.cluster-id`、`wms.tcc.transaction-group`、`wms.tcc.servers`。一个进程固定一个物理cell，Seata应用和资源身份均按cell稳定隔离，避免无可用资源时回退投递其他cell。资源使用完整SHA256的Base64URL，总长56字符；应用短标识保留128位哈希，总长29字符，兼容TC application_id和追加网络身份的client_id长度。仓路由须预先持久化，不能根据启动参数自动创建权威路由。

原生Seata 2.6.0的[RMClient](https://raw.githubusercontent.com/apache/incubator-seata/v2.6.0/rm/src/main/java/org/apache/seata/rm/RMClient.java)没有TM的四参密钥接口；本配置明确要求`wms.tcc.rm.network-isolation-confirmed=true`，部署侧须落实TC私网ACL，不能将此布尔值当作ACL测试证据。RM若收到不支持的access/secret配置则拒绝启动，不静默忽略。尚未验证生产TC网络授权或加密。

本地Try最多4个并发，每企业2个；每秒全局32次/企业16次，不排队。Fence和业务的外层本地事务统一10秒预算。SDK RPC回复等待1500ms、禁用批量发送；这不代表包含建连的端到端1.5秒，因为官方客户端仍有独立的建连/连接获取窗口。显式注册官方全局状态响应处理器后，健康线程每5秒执行不创建事务的TC状态查询，健康HTTP只读采样结果，超过10秒没有新成功样本则DOWN。不存在的探针XID返回Finished不能当作任何业务终态证据。SDK二阶段线程/队列保留官方有界实现，不能把其他配置键误称为已缩小到64项。

TM适配器使用官方`begin`及原XID`reload`提交/回滚，内部提交/回滚重试限制为一次；进入时拒绝已有RootContext，退出清理本次上下文。停止接纳新调用后最多等30秒，再销毁客户端；超时仍按未知结果恢复。真正放行继续依赖独立持久化TC终态证据，不依赖SDK调用返回。

## 迁移限制

仓复制清单新增本意图表，共48张。使用原生RM的仓暂时拒绝进入迁移停写：本地CONFIRMED/CANCELLED并不能证明TC已收到回执，也不能证明旧资源再无回调。需要后续TC终态通知及资源迁移验证才能解除此门禁，不能删除意图或手工改状态规避。未使用原生RM的现有迁移路径保持原有约束。

## 验证记录

2026-09-13 04:00:48，`/tmp/wms-runtime-rm-native-sixth-it.log` BUILD SUCCESS，正式TM适配器、真实TC、3个MySQL和两个库存Jar验证通过。B停机后保持TRIED，重启由TC按原资源/branch确认；两仓回滚后仅撤销本次TRIED数量；重复HTTP不新增分支，其他cell没有写入该分支的Fence；TC断连/恢复使readiness正确下降/恢复。

04:04:52，`/tmp/wms-runtime-rm-final-it.log` BUILD SUCCESS，最终定向组合13项通过：TM/审计4、原生RM进程1、登记/Fence数据库原子性2、仓迁移6，失败/错误/跳过均0。包含跨企业/仓服务权限、非法TC地址、超大及小数版本拒绝、登记未知不重复、最终Try与Confirm写失败整体回滚、空回滚路由检查、48表迁移清单和本地Confirm不可替代TC终态门禁。必需清单追加4项，现82项，数量不代表已做这一版本的全仓组合回归。

开发轮次曾暴露迁移号冲突、测试预算参数、SDK接口/响应处理器、资源及应用长度、NAT通告地址等问题，均已按实际API/DDL修正；失败日志不作为通过证据。Compose仅静态检查，默认RM关闭，未启动共享或生产栈。生产ACL、TC终态通知/资源迁移、履约持久化自动执行器和序列号身份剩余链路仍待，不能把当前定向证据标为完整R14或50AC验收。

必需用例门禁82、公开API契约87路径及文档检查通过；四个业务Jar独立smoke通过，日志`/tmp/wms-runtime-rm-smoke.log`。04:06:38 SBOM与许可证刷新完成，组件173/purl163，OSV仍为原有2项命中，未升级依赖。当前仅将既定Seata版本用于履约TM适配，未宣称已有风险消除。

## 创建attempt重试

V017为HTTP创建attempt命令追加企业范围幂等回执，服务端规范请求包含原订单、显式截止时刻、固定参与仓及数量/单位。省略截止时刻只在首次受理时生成默认一小时，重试返回原截止时间与原attempt；不同请求复用键拒绝。旧命令即使已有后续活动attempt也返回历史原attempt。命令占键、参与行、订单活动指针和最后回执绑定在同一事务中，不为历史未知键回填事实。

04:14:20 `/tmp/wms-attempt-command-it.log` BUILD SUCCESS，AttemptCommandIT 3项及FulfillmentHttpIT 2项通过，覆盖并发同键、丢回执、截止不续期、异内容拒绝、最后CHECK失败全回滚及真实HTTP重放。此接口仍只准备固定参与者，执行器另行接线。
