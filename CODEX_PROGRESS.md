# Codex Progress

## 任务目标

按用户最新“按照修改之后的工作流继续”，完成剩余 R13/R14/R15/R22。唯一有限验收在 docs/delivery/wms-v1/DELIVERY_PLAN.md 的“剩余整改有限验收”，顺序 OUT → WATERMARK → TRANSFER → TC → COMP → FINAL。沿用隔离组件测试与正常 Git 发布授权，不部署生产、不操作共享数据、不使用子 Agent。

## 已完成

- 文档17份已发布main4812941；已整合最新控制台/文档，根控制台工作区不动。
- OUT已发布：PICK dd22cd0、SHIP 1dd3d18、整合365a1eb；任务分支与远程main均已推送并核对祖先关系。main CI 34725376702运行中，不推同ref取消它。
- 后端基线3e2c720已发布普通消息、真实TM/TC/RM、序列入库及盘点。本地PICK提交dd22cd0和既有验证证据保留。
- SHIP已补恢复器、稳定原事实HTTP调用、SHIPMENT恢复查询/审计重排、原订单行可发运SN查询和公开契约。原发运证明单独含schemaVersion，不把库存POSTED当全球登记完成。
- 首次相关模块编译打包通过，日志.local/serial-shipment-compile.log，14.019秒，未运行测试。
- 定向数据库验收 `.local/serial-shipment-second-it.log` 已通过：OutboundPickIT 7、OutboundReservationPostingIT 4、SerialRegistryHttpIT 3，共14项。覆盖分批身份、最终写回滚、原证明重放、审计与旧执行器隔离；库存登记故障采用端口夹具。
- 修复既有OpenAPI生成器多行参数缩进，OpenApiContractTest 5项通过；最新内部发运接口加入后重新生成91路径。
- `.local/serial-shipment-process-it.log` 07:21:44 BUILD SUCCESS：来源HTTP2、登记HTTP3、双库迁移6、实际进程1；已验证分批扣账失败重启、真实登记已提交但回执丢失和再次重启恢复。当前无Maven在运行。

## 已修改文件

- outbound 来源SHIP/身份额度/可发运查询、inventory 原预占扣减/发运意图/持久恢复、registry 独立SHIPPED及历史凭证；V018/V042/V005追加迁移。
- 原有来源、库存、登记HTTP IT补部分发运/重复/最终写失败/审计重排/旧领取代际测试；git diff为准。
- scripts/generate-openapi.py、OpenAPI及scope（91路径）；DELIVERY_PLAN有限验收与本文件。

## 未完成

- OUT代码、定向验收和main发布已完成；远程CI仍在运行。
- WATERMARK：可信来源关闭/库存过账/回执水位，禁止调用者字符串充当完整证明。
- TRANSFER：公开序列调拨；TC：原资源/Fence/终态通知迁移；COMP：全局提交后取消补偿；FINAL：最后组合、R22结项及远程CI。
- 外部 OQ-03、AC-26现场黑盒、真实WCS、容量/RTO/RPO和生产历史时间不能伪造。

## 当前问题

- 唯一后端工作树 /Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate，已切新任务分支fix/reconciliation-watermark，基线365a1eb。发货分支fix/serial-outbound-execution保留。水位有未提交代码，未发布。
- WATERMARK已补来源端：runtime SourceWindowService/Mapper；inbound V017、outbound V019的范围屏障和分页证明表；两来源SourceProtocolService的T1在范围锁之后取时间；两个受信HTTP Controller及默认关闭配置。collect每页200/查询201，缺T3不推进，read只输出完整窗口，原范围/时刻/事实链式摘要。
- 初版数据库触发器在MySQL binlog下因应用账号无SUPER失败（.local/source-window-it.log），已移除触发器，未提升权限/修改环境。改用应用事务屏障，所有旧来源写节点退出后才能开启WMS_RECONCILIATION_WINDOW_ENABLED及受信主体；不得宣称新旧写节点可同时签发可信窗口。
- `.local/source-window-application-guard-it.log` 07:33:41 BUILD SUCCESS，SourceWindowIT2：201条分页/缺回执/最后写失败/重启摘要一致、在途T1阻塞关窗、拒绝关窗前新命令和其他仓不受影响。T3是明确数据库夹具。
- 最终来源定向复验.local/source-window-final-it.log于07:38:34成功（SourceWindowIT2/来源HTTP3/契约5）；入库回归5已有通过。固定数组摘要跨语言向量.local/source-window-digest.log于07:39:23成功。无Maven在运行。
- 旧main CI34723887946的JobCatalogClusterIT观察到19/20次触发而失败，10种效果各一次；旧日志.local/previous-main-ci-failed.log。新CI需跟踪，不能称旧CI通过，也不为碰运气重复整套检查。
- 定向数据库验收包括原身份分次发运、回执丢失/最后本地写失败、错证明、审计回滚、租约接管旧回执；其中登记端口故障夹具不称真实网络证明。
- 不在Maven运行中修改源码；输出重定向.local，只读结果/首个相关错误；同类失败两次复核原因。
- 原根控制台分支和文档分支保持不动，不从真实.env获取测试凭据。

## 下一步建议

1. 来源证明提供端已补契约（93路径）、配置与滚动说明，UTC只用DatabaseInstants.require；当前待暂存检查和独立逻辑提交。正式边界与证据在docs/implementation/RECONCILIATION_WATERMARK.md。不要重跑已通过检查或恢复触发器方案。
2. 核心缺口仍未修改：库存StockInternalReconcile.closeWindow仅凭非空字符串置complete、SnapshotExportService直接接受字符串。需要库存侧受信HTTP采集、持久逐页核对两个来源的原command/action/执行ID/postedQty/postingId及截止前库存凭证、完整计数/摘要，证据未齐不导出完整快照。不能建一个任意调用方可置complete的接口替代。
3. 关闭时间还要防库存流水的迟提交/后补旧时间造成历史快照漂移。数据库触发器方案已因权限否决，不要重试提升权限；应复用明确的应用事务屏障及旧写节点退出前置。明确cutoff是排他边界，晚于cutoff才过账的来源必须保持不完整，新窗口再重做。
4. 已通过OUT证据可复用，勿重跑整片。main CI34725376702仍需跟踪，旧双执行器19/20失败留在最终门禁；不推同ref取消进行中CI。
5. WATERMARK完成后继续TRANSFER/TC/COMP/FINAL，按有限清单逐片验证发布。

## 恢复 Prompt

先读本文件和唯一计划，核对git status与正在运行的Maven。从OUT未完成继续，不重做已发布入库/盘点，不丢弃SHIP，不把部分成功当全部整改完成，不等待逐片“继续”。
