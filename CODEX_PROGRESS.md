# Codex Progress

## 任务目标

完成已批准的后端24项整改；用户已确认“按收货分批，然后继续剩余四项”R13/R14/R15/R22。持续授权任务分支提交、正常合并推送远程main，不部署生产。

## 已完成

- R01–R12、R16–R21、R23–R24已有实现和定向验证，详见BACKEND_REMEDIATION.md。阶段版本已发布origin/main f9710ef。
- 独立工作树 `.local/backend-remediation-integrate` 的 `fix/backend-review-remediation` 新增7个已提交切片：26c4472分批质检；9a9b9fc分批上架/批次列表/前端；8019a45有界对账检查点/归档候选规划；1247334数据库时间策略；bb3651e消息健康抖动/CI去重复；1d645e2登记转移/盘点HTTP与原始事实重放校验；455115c有界登记HTTP/持久化恢复/审计重排。尚未推送这7个切片。
- RECEIVE/QUALITY/PUTAWAY真实Kafka双进程验证通过；控制台typecheck、33测试、build通过。当前批次权威身份为原RECEIVE commandId。
- R22统一数据库时间来源、迁移规则、所有生产MyBatis时间映射和v2时间游标；跨JVM/UTC与已验证+08历史存储库测试通过，不转换历史数据。
- R15过期巡检、租约回收、快照续跑、盘点逐行恢复、有界内部对账及归档候选规划已有实现/测试；候选规划不宣称导出或删除。
- R14登记服务独立库、受控主体/企业/scope/仓权限、命令审计、认领/激活/失踪/盘盈/转移入口已通过真实HTTP/MySQL验证。

## 已修改文件

- 已提交455115c的R14/R15切片：inventory/serial下有界HTTP调用器、凭据配置、恢复意图Mapper/Service、受审计恢复Operations/Controller；V033/V034；InventoryPersistence、InventoryCatalogJobs；本地序列号重放/桶校验及显式stage-only入口；对账序列号物理状态范围。
- 测试：SerialRegistryHttpClientTest、SerialRegistryProcessesIT、MasterdataHttpIT新增恢复HTTP用例，以及序列号/盘点夹具注册新Mapper。
- 登记服务修复首次ACTIVE缺receiptOperationId，并限制旧记录补齐只属于原始、未转移认领；补FOUND旧数据重放测试。
- 配置/文档：inventory application.yml、.env.example、库存test依赖registry确保干净reactor先打包真实服务Jar、OpenAPI生成器/产物/公开scope目录、required-its-default新增3项（总61）、SERIAL_REGISTRY_RUNTIME.md。

## 未完成

- 当前迁移切片完成验证，正在提交；当前无运行Maven。日志 `/tmp/wms-migration-validation-final-it.log` BUILD SUCCESS；此前序列号切片455115c已提交。
- R13：PICK/SHIP/CANCEL真实消息链、序列号观察及质量/移位对应身份，可信来源水位。
- R14：库存运行消息路径接序列号stage-only入口、转移来源释放可靠传播；盘点逐序列号持久化登记进度；真实fulfillment TM / inventory RM / TC终态证据及出库授权传播。
- R15：serialTransferRecovery执行器及人工恢复已实现但待当前验证/提交，完整运行来源链仍依赖R13/R14。归档导出/删除不在已验收成果中。
- 仓迁移47张表、时间来源、目标隔离、冲突回滚、冻结保护和部分切流重放已验证；整体在途TC/Fence回调及所有后台写入排空仍须随R13/R14核验，不能把当前迁移夹具当生产演练。
- 最终默认全量、warehouse/tc/failure profiles、远程CI、SBOM最终图核验。
- 外部业务验收仍有OQ-03、真实WCS、实际容量签署/隔离环境、50 AC；不能伪称完成。

## 当前问题

- 只在 `/Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate` 工作，所有exec显式workdir。根工作区仍main f9710ef，保护用户工作区。不得并发Maven或编译中修改源码。
- R14新HTTP适配器连接500ms、总请求1500ms、正文64KiB、8全局/2租户并发、32/8每秒、无盲重试。服务JWT外部文件按企业SHA256命名并轮换，不存Git，不转发用户token。
- 原始恢复意图+HOLD同事务，恢复网络在库存事务外，领取epoch和本地version防旧写；最多12次后隔离。人工重排需要messaging.recover、期望epoch与reason，审计同事务且claim_epoch只增不重置。
- 盘点原同步领域方法每行可能多序列号；直接接有界HTTP会因限流整体回滚并每次从头开始。已撤销这种过早接线，必须先做逐身份持久化进度。
- 新双库进程测试01:27:51通过，真实登记完成激活/转移确认后丢回执、库存最后提交失败均恢复且库存一次；日志 `/tmp/wms-registry-process-recovery-it.log`（此前失败已修复receiptOperationId缺失）。
- `/tmp/wms-serial-recovery-final-it.log` 01:32:05失败两处：新Controller未捕获WarehouseForbiddenException导致500（已补403处理）；新序列号数量断言未种SKU被JOIN过滤（已补SKU）。同次SerialReceiptIT、SerialTransferRecoveryIT、CountSerialIT、StockInternalReconcileIT通过。当前session72637重跑MasterdataHttpIT、SerialRegistryProcessesIT、SerialRegistryHttpIT及依赖单元。
- 远程main及任务分支f9710ef的CI均已结束失败。main 34704623423失败原因是健康UP/DOWN抖动，bb3651e已修复并本地验证；新切片尚未推送，不能称远程CI已通过。不得取消别人CI；推同ref前检查运行状态。
- 共享/生产旧库历史时区未审计、未转换；部署前需真实legacy-evidence。Tomcat/fastjson既有安全问题保留记录，不借此盲升级依赖。

## 下一步建议

1. 当前无运行Maven。455115c已提交，当前仓迁移数据与隔离修复正在提交。随后继续R13出库消息、序列号观察与R14真实TM/TC、盘点登记进度。新required默认64项，OpenAPI87路径。
2. 接R13序列号观察/出库消息与R14真实TM/TC、盘点登记进度，补迁移清单/排空验证。继续原计划，不重新问“继续”。
3. 全部必要检查通过后推任务分支、fetch/main集成、正常推HEAD:main并检查远程CI；根用户工作树保持不变。

## 恢复 Prompt

读取CODEX_PROGRESS.md、docs/delivery/wms-v1/DELIVERY_PLAN.md和BACKEND_REMEDIATION.md，按已确认的分批质检口径继续剩余四项。仅在独立集成工作树操作，核对当前Maven、Git与CI状态，连续推进未完成部分；不要把阶段验证/提交宣称为24项或50 AC全部完成。
