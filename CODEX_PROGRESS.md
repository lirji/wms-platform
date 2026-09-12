# Codex Progress

## 任务目标

完成后端24项整改中剩余R13/R14/R15/R22。用户已确认“按收货分批，然后继续剩余四项”。持续授权独立任务分支、逻辑提交、验证后正常合并推送远程main；不部署生产。

## 已完成

- R01–R12、R16–R21、R23–R24已有实现/定向验证，阶段版本origin/main f9710ef。不是50 AC整体通过。
- 11个业务切片和验证记录95a1d46已推送到任务分支及远程main；业务切片：26c4472分批质检；9a9b9fc分批上架/批次入口；8019a45有界对账/归档候选；1247334数据库时间来源；bb3651e消息健康/CI去重复；1d645e2登记转移/盘点HTTP；455115c有界登记调用/持久化恢复/审计重排；3ac54b9仓迁移47表/目标隔离/冻结与部分切流恢复；4b8f7c5只读TC证据与有界分配恢复屏障；5d1219d库存确认可靠消费和Inbox兼容迁移；c5348d2出库消息/原订单行预占与桶级额度。
- RECEIVE/QUALITY/PUTAWAY真实Kafka、双服务Jar、双MySQL闭环通过；批次以原RECEIVE commandId固定，质量累计版本与上架额度均校验。控制台typecheck、33测试、build通过。
- 登记服务真实HTTP、受控主体/企业/仓鉴权、转移原始epoch/ref、FOUND旧记录修复通过。库存有界HTTP与凭据轮换、丢回执/本地提交失败恢复、人工重排通过（/tmp/wms-serial-route-final-it.log 01:35:57）。
- 仓迁移最终5测试通过（/tmp/wms-migration-validation-final-it.log 01:47:05）。不代表真实TC/Fence搬迁与全后台排空已验证。
- TC切片已提交4b8f7c5：JdbcTcStatusPort只读审计、明确TC集群/TM来源绑定、有界恢复游标、TC查询不持业务锁、陈旧回写拒绝；终态不可覆盖、分支确认必须有完整身份、当前attempt校验、Outbox约束失败原子回滚和重复内容核对。
- TC切片最终证据：/tmp/wms-tc-fulfillment-final-it.log 02:05:32 BUILD SUCCESS（4个真实TC/DB新用例+7个履约回归）；/tmp/wms-tc-barrier-combined-it.log 02:04:03 BUILD SUCCESS（重新编译当前跨服务源码，ClosedLoopBlackBoxIT 1及依赖单元）。真实TC用例的仓确认来自明确夹具，不能算完整库存RM链路。
- standalone warehouse-it 01:09通过；standalone tc-it /tmp/wms-ci-tc-only.log 01:50:06通过2个大探针。没有在同工作树并发Maven。

## 已修改文件

- 当前TC切片：wms-fulfillment新TcEvidenceScope/Mapper/Configuration/JdbcTcStatusPort、AllocationRecoveryMapper和V013；AllocationRecoverySweep/Job、FulfillmentService/Mapper/Persistence、application.yml、测试scope Seata依赖和复用TC测试资源。
- TcAuditRecoveryIT及原3个履约夹具、.env.example、scripts/required-its-default.txt（现70必需用例）。OpenAPI仍87路径。
- docs/implementation/FULFILLMENT_TC_RECOVERY.md及交付计划/状态/整改记录。

## 未完成

- R13：PICK/SHIP/CANCEL已完成本地真实消息验证；序列号观察/质量/移位及可信来源水位仍待。
- R14：真实fulfillment TM发起、inventory RM服务调用和出库授权传播（仓确认可靠消费已验证）；库存消息接序列号stage-only入口、来源转移释放可靠传播；盘点逐序列号持久化登记进度。
- R15：七个catalog handler已实现执行器，serial恢复来源链仍依赖R13/R14；归档只是候选计划，未导出/删除，未编造保留期。
- R22代码与定向证据已完成，未审计/转换共享或生产历史时区；全量默认223/必需74、failure3、smoke、SBOM均通过；远程CI仍待。
- OQ-03、真实WCS、真实容量签署/隔离环境、50 AC业务验收仍有外部工作，不能伪称全部完成。

## 当前问题

- 当前HEAD c5348d2。全仓clean verify于03:12:03 BUILD SUCCESS，耗时18:30，108个测试类/223用例，失败/错误/跳过均0；required default 74通过，四个Jar独立进程smoke通过。日志/tmp/wms-c534-default.log与/tmp/wms-c534-smoke.log。standalone failure-it于03:13:24通过3项，必需门禁通过，日志/tmp/wms-c534-failure.log；SBOM于03:14:25生成完成，173组件/163purl，OSV仍为原有2项，无新增命中；当前没有运行Maven。已正常推送分支并快进远程main到95a1d46，两个远程ref已核对。warehouse-it与tc-it仅依赖未变test-support，已有本地通过证据复用，远程仍重跑。
- 出库新增OutboundPostingService、原订单行冻结、PICK/SHIP/CANCEL真实消息、原行/桶/CONFIRMED预占消费、真实来源执行凭证、桶级发运额度和取消posted数量。支持指定桶取消qty，停止旧未完成拣货任务；删除本次原逻辑生成的无库位RESTOCK行为。消息关闭时保留旧无上下文客户端，启用时维度必填。页面已同步，typecheck、33用例及build通过，最新数量字段又经定向页面测试/build通过。
- 唯一工作目录：/Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate，分支fix/backend-review-remediation。根用户工作区main f9710ef保持不动。所有exec显式workdir；禁止并发Maven或编译中编辑源码/配置。
- 先前session64192已退出成功，当前所有Maven已退出成功；/tmp/wms-confirmation-replay-final-it.log 02:24:47 BUILD SUCCESS。
- 远程f971 main CI34704623423和任务分支34704615403均失败结束，原因健康UP/DOWN竞争已由bb3651e修复，本地已验证，新切片尚未推送。推同ref前核对运行CI，不能取消别人或强推。
- TC候选触发器仅隔离测试安装；应用不迁移TC库。旧attempt缺allocation_tc_binding保持显式待恢复，不按当前集群配置猜测补齐。TC审计enabled默认false，SELECT专用账号，4连接/1s语句/1.5s网络，健康5s缓存。
- TC返回Committed不等于立刻可读终态；真实测试复用候选探针的retryDeadThreshold=1000ms只缩短隔离测试清理窗口，不改生产配置/承诺RTO。
- 历史fulfillment父目录Inbox迁移缺口已由5d1219d用V014追加修复；旧手工Inbox保留正文/微秒/epoch的迁移测试本轮已通过，本轮验证已通过。
- 新ReservationConfirmed携带原attempt/allocation/action/route和confirmationSchemaVersion=1，投fulfillment.results；旧无版本保持inventory.events，不伪造旧事实。新未知版本Outbox隔离。履约Inbox接线已实现并真实双进程初验通过，最终复验已通过；完整TM/RM及屏障Outbox发布/出库消费仍待。
- 盘点不能直接注入HTTP循环：一行多序列号会限流整体回滚且反复从首个身份重试；必须先持久化每身份远程结果，远程在业务事务外，最终本地调整不再访问网络。现CountService同步端口只在领域测试使用，运行默认拒绝。
- 全局序列号设计还有SHIPPED/SCRAPPED/RETURN_CLAIMED，不能用MISSING替代正常出库；SEALED调拨来源不可作为物理数量；轮回转移需明确新epoch历史，不能清空旧事实硬重用。

## 下一步建议

1. c5348d2已提交，全仓Maven/failure-it/SBOM均成功，95a1d46阶段main发布已完成，CI运行中；继续真实TM/RM及履约Outbox发布/出库授权传播。当前fulfillment_order/创建DTO没有ownerId，真实执行不能猜测货主：需新增显式货主范围并保留旧未知记录拒绝执行；库存确认已有confirmed_allocation_id，但原屏障Outbox缺完整owner/allocation/TC来源载荷，不能把旧不完整事件当可执行授权。
2. 补R13出库/序列号观察与R14盘点逐身份登记；保持原有分批质检口径，不重新询问是否继续。
3. 全部必要检查通过后推任务分支，fetch/main正常集成并推HEAD:main，检查远程CI；根工作区保持不变。

## 恢复 Prompt

读取CODEX_PROGRESS.md和docs/delivery/wms-v1的DELIVERY_PLAN.md、BACKEND_REMEDIATION.md，在独立集成工作树继续已批准剩余四项。先核对当前Maven/Git状态，复用已有证据；不要把阶段提交、真实TC候选测试或handler存在当作完整业务验收，不要等待“继续”。

## 下一切片的已读上下文

- FulfillmentWorkbenchRequests/CreateFulfillmentRequest和fulfillment_order均无ownerId；不能从出库手工输入或SKU猜测。拟增加新创建显式ownerId，旧无货主记录在自动执行前保持拒绝；不修改已执行迁移或静默补齐旧事实。
- 原writeBarrierOutbox产生AllocationCompleted及每仓OutboundOrderRequested/ExecutionAuthorizationRequested，但正文只有attempt/XID/warehouse/reservation/lines，缺owner/allocation/TC来源/授权标识。已有allocation_tc_binding和confirmed_allocation_id可证明新记录来源，但旧NULL必须拒绝。发布不能改写原payload或用当前不相干配置猜测来源。
- 可考虑追加delivery_payload JSON，在原始绑定/确认/终态全部可验证时有锁地一次性固化完整投递上下文，后续重放沿用；或新版本独立事件。尚未实施，不要当成既定设计。
- 真实TM/RM需要独立服务凭据、固定仓路由/epoch、Try前冻结货主及库存桶或有界候选策略、原XID重试网关不重复branchRegister；TC官方Fence与业务同物理事务。现有ReservationTccAction仅领域Bean，没有运行Seata RM注册/HTTP Try网关；test-support HttpGatewayTryProbe/WarehouseRmProcess为官方API参考，不是生产服务。
- StockCommandService原applyPick/applyShip和InventoryApplicationService旧pickReserved/shipPicked只被历史领域夹具调用，运行消息现在走严格applyOutbound/postOutboundReservation。别把旧夹具当HTTP路径。

- 已修复compose.yaml出库消息前缀为${WMS_MESSAGING_TOPIC_PREFIX:-wms.local}；默认和wms.verify自定义前缀静态config均通过，未启动服务。Python4、文档39/链接86和87路径契约均通过。
- 授权传播建议在成功屏障事务内固化版本化delivery_payload，保留旧payload不改写；旧缺owner/TC来源/confirmed_allocation_id不自动推断。出库应核对同attempt重放的货主及全部原订单行，现createFromAllocation尚未核对重复内容。Outbox每轮采用20秒新领取预算，逐条短事务领取后网络在事务外。

- 下一切片仅有临时草稿：/tmp/wms-authorization-draft/AllocationAuthorization.java（独立javac通过）及AllocationAuthorizationMessage.java；尚未复制进仓库、未实施或验收。计划完整V1仓级授权快照/TC来源强校验，不能当成功链路。

- 组合验证归档：docs/implementation/REMEDIATION_VERIFICATION_2026-09-13.md。本次收尾只增加Compose前缀修复、SBOM和验证文档，无新增业务源码；应用源码验证依据c5348d2。

- 远程发布已核对：main与fix/backend-review-remediation均95a1d4626985fff5dfe95350478457e0d1bb1f25；main CI34713653573、分支CI34713636111进行中，不能再次推同ref取消运行。当前无本地Maven。继续履约授权切片，远程CI独立运行不影响工作树编辑。

## 当前进行中的授权切片（优先于上文历史状态）

- 当前分支HEAD仍95a1d46，远程main/任务分支均已发布该阶段。根用户工作树main仍f9710ef，不切换/覆盖。main CI34713653573与分支34713636111运行中，前端成功、Java仍组合验证；运行结束前不能推同ref。
- 新增仓级AllocationAuthorization契约与严格JSON边界、明确ownerId/同源原始行重放、成功屏障同事务delivery_payload、逐条有界FulfillmentOutboxPublisher、出库授权Inbox消费及本地完整证据。授权先于建单仍可恢复，普通建单不授执行权。实际文件均在当前独立工作树；不再只是/tmp草稿。
- 新迁移：fulfillment目录V016（owner、delivery_payload、领取索引）与outbound V016（barrier_payload）。保留旧NULL来源，不能猜测补事实；同owner原订单行/策略版本也要核对。
- 03:20:34编译成功；首轮授权测试因FulfillmentMapper.bindOutboxDelivery XML遗漏失败，已补齐，指定CHECK断言也已加强，不能用任意RuntimeException冒充注入故障成功。
- **当前唯一Maven session68767运行**，日志/tmp/wms-authorization-second-it.log，-pl wms-inventory -am；覆盖MessageRecoveryIT、AllocationAuthorizationSnapshotIT、FulfillmentHttpIT/BarrierIT、新双进程授权、原确认及出库消息。禁止并发Maven或修改Java/XML/配置直到退出。前端session39677已成功退出，33测试/typecheck/build通过。
- MessageRecoveryMapper对履约审计实际delivery_payload；重排严格校验完整授权及原范围，旧最小事件不可直接重排。新增MySQL用例覆盖原摘要、旧拒绝；应在本轮测试中核对。
- 文档docs/implementation/FULFILLMENT_AUTHORIZATION_MESSAGING.md已写实现与明确未验收边界。required门禁尚未追加新3项、交付状态未标本切片通过，等测试完成再同步/提交。
- 下一步：等68767结果，修复真实失败并定向复测；追加required3项/文档/契约门禁、逻辑提交授权切片。核验95a1远程CI；然后继续正式TM/RM与序列号/盘点/可信水位，不能停止在本切片。

- 授权切片更新：68767于03:28:53成功（9个定向IT+单元）；取消后原授权重放修正后62227于03:30:41成功。新增首次屏障前取消测试，当前唯一Maven78613（/tmp/wms-authorization-cancel-it.log）运行，只跑fulfillment/依赖；生产源码未再修改。required默认清单已追加4项，总78。当前仍未提交授权切片；下一步完成当前测试/文档与契约检查/逻辑提交，观察远程CI后继续TM/RM。

- 授权收尾：78613于03:31:35 BUILD SUCCESS，快照3用例含首次签发前取消；当前没有运行Maven。代码/迁移/测试/控制台已完成定向验证，必需清单78；文档41/链接90通过。准备提交授权逻辑切片，95a1远程CI未结束前不推同ref。
