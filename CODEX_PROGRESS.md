# Codex Progress

## 任务目标

完成后端评审剩余 R13/R14/R15/R22。用户已确认“按收货分批，然后继续剩余四项”。持续授权独立任务分支、逻辑提交、必要验证后正常合并推送远程main；不强推、不部署生产，不等待“继续”。

## 已完成

- R01–R12、R16–R21、R23–R24已有实现及定向验证；不代表50AC/真实WCS/容量签署或生产验收。
- 用户确认质检按每次收货分批。RECEIVE/分批QUALITY/PUTAWAY已通过真实Kafka、双服务Jar及双MySQL；批次以原RECEIVE commandId固定，累计质量结果及上架额度受约束。PICK/SHIP/CANCEL、原订单行、桶级预占/发运额度/取消回执已通过双进程消息验证。
- 序列号登记真实HTTP、货主仓主体校验、转移原epoch/ref、有界客户端/凭据轮换、持久化恢复及审计重排已验证。禁止把原盘点同步网络循环直接注入运行服务。
- 仓迁移复制清单48表（含inventory_tcc_intent），隔离目标/冻结/断点恢复已有真实双库测试；使用原生RM的仓仍拒绝迁移，不能把本地CONFIRMED当TC已收到回执。
- TC只读审计、来源绑定、有界持久恢复游标、终态不可覆盖、分支确认可靠Inbox及完整授权Outbox/出库Inbox已验证。旧无货主/完整TC来源事件不猜测补齐；授权先于建单可恢复。
- 95a1d46包含11业务切片及组合验证记录，main及分支CI34713653573/34713636111成功。授权切片bd6adf0的main及分支CI34715199124/34715195926也成功。
- 原生RM切片0a1ec85已提交并推送main/任务分支，两ref已核验。实际TM适配器、真实TC、双库存Jar；原branch重试、B停机恢复Confirm、真实Cancel、TC断连readiness和空回滚路由校验通过。最终13IT日志/tmp/wms-runtime-rm-final-it.log，04:04:52成功；smoke/必需82/文档/契约及SBOM通过，OSV仍2个既有命中。
- 创建attempt命令回执切片e10ef1b已提交，尚未推送：V017原命令/规范请求/原attempt原子绑定，省略deadline只首次生成，丢回执不新建/不续期，最后写失败全回滚。04:14:20定向5IT通过，日志/tmp/wms-attempt-command-it.log，必需85。
- 当前自动履约切片已通过首次真实完整网络测试：04:27:05 /tmp/wms-execution-process-second-it.log成功；实际fulfillment TM +真实TC+双inventory RM+outbound Jar+Kafka+5个MySQL。最后Try回执写失败重启后原XID/原branch不变，最终两仓出库授权；没有手工伪造TC终态或仓确认。随后补空XID证据和HTTP负例，需看最新复验结果。
- 04:22:40 /tmp/wms-execution-second-it.log：执行恢复4+创建命令3+HTTP2共9IT通过；04:28:53 /tmp/wms-execution-empty-it.log：执行恢复5+启动2+HTTP2共9IT通过，新增已知空XID绑定失败/TC证据清理，含V019。

## 已修改文件

已提交的历史切片及证据详见docs/delivery/wms-v1/BACKEND_REMEDIATION.md和docs/implementation/REMEDIATION_VERIFICATION_2026-09-13.md。

当前未提交自动履约切片：

- wms-fulfillment新增AllocationExecutionService/Worker/Mapper/Controller/Configuration、AllocationTmPort、WarehouseTryPort/HttpClient、Mapper XML及V018/V019。
- SeataTmDriver实现端口；FulfillmentPersistence注册Mapper；工作台GET显示execution；FulfillmentService/FulfillmentMapper的空XID清理新增原TC证据要求及持久化。旧无证据CLEANED不伪造回填。
- AllocationExecutionIT（5项）、FulfillmentLaunchIT证明已知空XID无TC证据不能CLEANED；AllocationExecutionProcessesIT实际四业务进程、真实TC/Kafka/5库；RuntimeRmProcessesIT仅测试辅助方法放宽包可见供复用。
- scripts/generate-openapi.py新增执行入口/权限/专用响应Schema（当前88路径）；生成OpenAPI/operation scopes；.env.example/compose默认关闭执行器配置。
- docs/implementation/FULFILLMENT_EXECUTION.md为最新范围、故障语义及配置说明；交付计划/必需IT/证据收尾尚待。

## 未完成

- 当前切片：等待最终组合复验，检查88路径契约/必需91（尚未追加6项）/文档/Compose/smoke/SBOM，逻辑提交；核对0a1远程CI后正常推送分支及main，不取消运行CI。
- R13：序列号观察/质量/上架/PICK/SHIP身份链及可信来源水位；新发现多物理cell共用库存Kafka消费者组，需要按cell可靠路由，避免消息分配到不持有该仓的进程。当前TM HTTP及确认/授权成功不证明普通库存命令已正确路由。
- R14：序列号stage-only消息入口、来源转移释放传播、盘点逐身份持久化登记进度；TC终态通知/原资源及Fence迁移；已全局提交的取消需要业务补偿（当前保持CANCEL_REQUIRES_COMPENSATION，不能将TCC Cancel用于CONFIRMED）。
- R15：七个catalog handler已有执行器；serialTransferRecovery来源链仍依赖R13/R14。归档仅候选计划，没有导出/删除，也没有编造保留期。
- R22代码及95a1/bd6组合CI通过；没有审计、转换或声明共享/生产历史时区。当前所有后续源码最终全量组合verify仍待。
- OQ-03、真实WCS、签署容量/RTO/RPO/50AC仍有外部验收工作，不能算此轮代码修复全部完成。

## 当前问题

- 唯一工作目录：/Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate；分支fix/backend-review-remediation。所有exec显式workdir。根用户目录保持main f9710ef，不切换/覆盖其他工作树。
- 当前HEAD e10ef1b，origin/main及任务分支0a1ec85e5a1231a41ef8291cbd2be1be005cbde5。0a1 main CI34716410427、分支34716400385进行中，推同ref会取消运行CI，先等结束。
- **89441已于04:32:00成功退出，当前没有Maven运行**：/tmp/wms-execution-final-it.log，-pl wms-inventory -am，AllocationExecutionProcessesIT、AllocationExecutionIT、FulfillmentLaunchIT、FulfillmentHttpIT。禁止并发Maven或编译中修改Java/XML/配置。之前所有session已退出。
- 本轮首次执行测试因OpenAPI参数多行缩进错误失败，已修复；首次进程测试因RSAKey同名导入编译失败，已修复。不把失败日志改称通过。
- TC审计触发器仅隔离测试安装，业务应用不写TC库；生产端口用SELECT专用账号。TC返回Committed与持久证据可读有时间差。既有full verify证据：c534源码223用例/108类，03:12:03成功/tmp/wms-c534-default.log；95a1/bd6远程全默认及warehouse/tc/failure/console全成功，不覆盖当前未提交源码。
- 自动执行器：begin前持久BEGIN_CALLING，不盲重启；原XID绑定后才Try；每仓回执与进度原子提交；COMMIT/ROLLBACK意图固定；8次RPC后不继续发送，只读TC等待。60秒租约，旧代际拒绝写回；每企业每轮1动作，配置最多64企业轮询。HTTP500ms连接/1500ms总等待/64KiB响应，TC真实端到端包含SDK连接窗口，不能声称总1.5秒。
- 凭据由受控目录按SHA256(enterprise)+.jwt原子轮换，主体wms-fulfillment，scope inventory.tcc.try，不转发操作员JWT。启用需要消息、TC审计、企业和cell允许列表；Compose还需实际只读挂载目录，默认不启用、不生成JWT。

## 下一步建议

1. 等89441退出，检查最终10IT和源码实际状态。补执行器阶段证据、required6项（85→91）、公开88契约及逻辑提交；0a1 CI成功后正常快进发布。
2. 继续R13多cell可靠消息路由与序列号身份入口，再完成逐身份盘点/来源释放/可信水位、TC资源迁移与取消补偿。
3. 序列号关键约束：SerialReceiptService.stageHold会加physical1，不能在聚合RECEIVE已加量后调用；需原收货批次绑定的stage-only身份入口。CountService运行registry=null保持拒绝，不能把逐SN远程循环放整个行事务；先逐身份持久化远程结果，再本地数量/序列号原子提交。observeIdentities现不接受空seenSerials，全丢失需修复。FOUND本地AUTHORIZED跳过仍需检查SKU/桶，历史owner_epoch/receipt_operation_id需正确保存。
4. 正常SHIP不能用全局MISSING状态替代；SEALED来源不是物理数量；调拨复用身份必须保留原epoch/ref及轮回历史。旧transfer事实不能通过清空来重用。
5. 全部必要检查通过后推送远程main并核对CI；不操作共享dev_infra、生产数据，不删除分支或工作树。

## 恢复 Prompt

读取CODEX_PROGRESS.md和docs/delivery/wms-v1的DELIVERY_PLAN.md、BACKEND_REMEDIATION.md，在唯一独立工作树继续用户批准的剩余四项。先核对记录中的Maven是否仍运行，禁止并发Maven/编译中改源码。当前自动履约切片最终复验89441已通过10项，尚待逻辑提交；完成验证提交后继续序列号、逐身份盘点及可信水位，不停在阶段提交。没有业务完成证据不能标全部完成，不等待“继续”。

最新收尾：最终10IT已通过，必需清单已追加为91，契约专用执行响应共88路径；文档已同步。SBOM因POM/依赖未变复用0a1证据。当前准备默认关闭smoke、契约/文档/Compose检查及提交，之后继续多cell库存消息路由。

执行器收尾检查通过：四个实际Jar默认关闭smoke、required91、88路径契约、文档43/链接100、Compose静态config与diff检查。当前没有Maven或smoke运行，准备逻辑提交；0a1远程两路Java仍在运行，不推同ref。

## 当前多cell路由切片

- 自动履约切片已提交2e8ddf4，连同e10ef1b尚未推送。0a1main正在TC专项，分支还在全量Java，不推同ref。
- 新InventoryCellRouting：配置化企业仓/cell/代际清单、每cell+清单版本独立消费组；只跳过明确其他cell，未知仓/非法信封仍落Inbox，业务事务校验数据库ACTIVE/代际。RM+消息启用时必须配置清单，防止默认共用无路由组。
- 顺带修正消息信封和来源Outbox版本整数溢出不能降级V1；新增路由单元边界及实际双cell普通收货/陈旧路由/未知仓隔离探针（在原自动分配进程测试内）。当前准备运行，尚未验收。

多cell首轮51695失败：真实两仓收货APPLIED及无跨仓Inbox断言已到达，后续测试误用physical_qty列，改为实际on_hand_qty；陈旧路由隔离断言同时精确到ROUTE-STALE原事件。0a1main CI34716410427成功，分支34716400385失败，唯一失败RuntimeRmProcessesIT在B回调CONFIRMED后过早HTTP导致Connect；已补B的HTTP readiness等待。修复未验证前不发布后续提交。当前准备同批复验。

Maven session83153已04:42:04成功退出，日志/tmp/wms-cell-routing-second-it.log；RuntimeRmProcessesIT和AllocationExecutionProcessesIT共2项通过及依赖单元通过。当前无Maven运行。CI readiness修复已独立提交7ec2fbc；多cell路由准备静态检查后提交。e10ef1b/2e8ddf4/7ec2fbc尚未推送；0a1远程两路CI已结束，main成功、分支失败已由7ec2fbc修复。

后续序列号已读：InboundReceiptService.bindReceiveContext通过SourceCommandContextStore.bind同事务固化来源command/Outbox，目前不含serials；ReceiptQualityService只有累计质量数量，没有明确身份列表。StockCommandMessageHandler对serial_enabled且非CANCEL仍返回SERIAL_OBSERVATION_REQUIRED。需新增按收货批次的明确序列号列表、原列表重放校验、质量和移动身份，不能按数量猜测哪些SN受检。

多cell收尾：04:42:04复验通过2个进程IT；契约88/必需91/文档44、四Jar smoke通过。Compose先因必填变量缺失失败，随后对compose.yaml与include的deploy/compose.local.yml均填仅解析占位值，静态config通过，未启动服务。当前准备提交并推送，之后继续序列号来源绑定。
