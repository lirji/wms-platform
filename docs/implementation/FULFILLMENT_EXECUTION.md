# 持久化自动分配执行

本切片已通过定向和实际进程验证，不能当作完整R14或50AC验收。它接通实际履约TM发起、固定桶HTTP Try、原XID提交恢复，并复用已有TC只读审计、库存确认Inbox和出库授权Outbox。

## 受理与固定输入

`POST /api/wms/v1/fulfillments/{fulfillmentId}/attempts/{attemptId}/executions`需要`fulfillment.execute`、企业和所有参与仓权限及`Idempotency-Key`。请求的`warehouses`数组元素使用[仓级Try V1契约](../../wms-contract/src/main/resources/contracts/warehouse-tcc-try-v1.schema.json)。`allocationId`与`attemptId`均须为原attempt，企业、货主、全部仓/原订单行/数量/单位/最小剩余效期与已保存订单及计划逐项比对。一个订单行可明确拆分多个库存桶，不能临时换仓、猜货主或隐式选择批次。

每个attempt只接受一个执行命令；规范摘要包含原履约单、完整规范Try正文和TC来源。V018追加`allocation_execution`，V019为已知空启动保存TC清理证据，首次操作员身份、请求、原TC环境及启动身份固定。202仅表示受理落库，工作台履约详情的`execution`字段显示实际进度。旧无货主、已经手工启动、过期或已取消的attempt不能自动执行。

## 单步恢复

每次领取一个企业的一条到期任务。短事务先写租约代际，网络调用结束再按代际回写；网络期间没有打开的业务事务。启动前持久化`BEGIN_CALLING`及原launch所有权，之后只调用一次begin。启动结果未知或重启发现该阶段时进入`BEGIN_UNKNOWN`，不会因为租约过期再begin。

原XID及TC来源绑定成功后才调用任何仓Try。begin返回但绑定失败时先读取持久化结果；确实未绑定的已知空XID保存到原launch及执行记录，另行请求原XID回滚，只有真实TC回滚证据到达才标清理。无XID的未知启动保留人工审计边界，不虚构空事务已结束。

各仓成功回执与本地参与者身份/进度同事务保存。最后写进度失败时整笔本地回执回滚，但库存可能已预占，下次仍使用原XID、原请求从RM读取同一分支。旧领取代际不能覆盖新执行器结果。

全部仓回执成功后持久化一次`COMMIT`调用意图；取消、截止或Try重试耗尽时持久化`ROLLBACK`意图。该字段不是全局权威decision。意图固定后不因RPC超时改方向，原XID恢复调用不再begin。SDK调用返回也不标ALLOCATED：必须读取原TC持久化终态，并满足所有仓可靠确认及已有业务屏障后才能写授权Outbox。

## 预算与运行配置

默认关闭。需要开启`wms.fulfillment.execution.enabled`，同时配置消息链路和[TC只读审计](FULFILLMENT_TC_RECOVERY.md)。TC应用固定`wms-fulfillment`，集群及分组使用审计配置；`tc-servers`必须明确，`network-isolation-confirmed`仅表示部署声明，不能代替ACL验证。

`cells-json`是受控cell到HTTP(S)根地址映射，最多64个，不接受用户URL；HTTP需显式`allow-http`。`enterprises`明确本进程服务的企业，最多64个。`token-directory`由受控凭据方挂载：每个企业的SHA256摘要文件名加`.jwt`，使用`wms-fulfillment`主体及`inventory.tcc.try`权限，限制企业和仓。逐次读取支持原子轮换，最多16KiB且不跟随文件符号链接，不转发操作员JWT。Compose传递默认关闭的配置；启用时需额外只读挂载实际凭据目录，默认配置不生成令牌。

后台一个线程轮流推进企业，每次一个外部动作；同企业单实例最多一个在途调用。每条计划最多60KiB/200仓，单仓最多200桶行。每个Try阶段最多8次，完成仓后重置该仓重试数；提交/回滚RPC最多8次，其后继续低频只读TC证据观察。60秒租约与条件回写阻止旧代际推进。HTTP连接500ms、总等待1500ms、响应64KiB、4线程/64队列；官方TM事务预算1至60秒，RPC回复等待1500ms，但SDK还有独立连接窗口，不能承诺整个TM调用1.5秒。此为限制配置，不是容量实测结论。

## 保留的边界

- 首次授权前收到晚取消，但TC已经Committed：保持`CANCEL_REQUIRES_COMPENSATION`且不签发授权。业务补偿仍须另行实现，不能向CONFIRMED分支发TCC Cancel或把取消标志当作库存已释放。
- 原生RM仓的TC终态通知及资源迁移门禁仍遵循[原生RM限制](RUNTIME_TCC_RM.md)，不能删除意图解锁迁移。
- 多物理cell当前共享Kafka库存消费者组的路由仍须整改；本执行器的仓级HTTP和履约确认/授权链路不证明所有入库、拣货、发运消息已正确按cell投递。
- TC终态审计触发器仍是隔离环境候选，不由业务应用向共享/生产TC安装；无相应审计时不能启用自动执行。

## 验证

`/tmp/wms-execution-second-it.log`于2026-09-13 04:22:40成功，9个IT通过（执行恢复4、创建命令3、HTTP2）。执行恢复使用真实MySQL和明确的故障端口夹具，验证最后回执失败、重启、未知begin不重开、旧代际拒绝、提交意图不可翻转及8次Try重试上限；端口夹具不能代替真实TC/RM网络验收。

04:27:05 `/tmp/wms-execution-process-second-it.log`首次真实闭环通过。04:28:53 `/tmp/wms-execution-empty-it.log`通过9IT（执行5/启动2/HTTP2），包含绑定失败的已知空XID只按真实回滚证据清理。

最终 `/tmp/wms-execution-final-it.log`于04:32:00 BUILD SUCCESS，执行5/启动2/HTTP2/真实进程1，共10项通过。实际四业务进程、真实TC及Kafka、5个MySQL验证：原货主/仓权限/溢出版本拒绝，真实B Try后最后履约进度写失败，重启读取原分支并用原XID提交，双仓可靠确认到达履约，再可靠下发出库授权。TC仅有一个终态事务，两库存仍各自只有一个原意图，出库两张授权数量正确。日志位于`wms-inventory/target/allocation-execution-processes/`。首轮OpenAPI缩进和进程测试RSA导入编译失败已修复，不计为通过证据。

依赖及POM没有变化，复用0a1ec85的SBOM/许可证/OSV证据（173组件/163purl、2个既有命中），没有新增依赖或升级中间件。
