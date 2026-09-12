# Codex Progress

## 任务目标

按用户最新“按照修改之后的工作流继续”，完成剩余 R13/R14/R15/R22。唯一有限验收在 docs/delivery/wms-v1/DELIVERY_PLAN.md 的“剩余整改有限验收”，顺序 OUT → WATERMARK → TRANSFER → TC → COMP → FINAL。沿用隔离组件测试与正常 Git 发布授权，不部署生产、不操作共享数据、不使用子 Agent。

## 已完成

- 文档17份已发布main4812941，最新控制台与文档在远程main；源码切片验证后整合，根控制台工作区不动。
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

- OUT代码和定向验收已通过；已提交1dd3d18；当前待完成main文档/控制台整合、必要门禁与发布。
- WATERMARK：可信来源关闭/库存过账/回执水位，禁止调用者字符串充当完整证明。
- TRANSFER：公开序列调拨；TC：原资源/Fence/终态通知迁移；COMP：全局提交后取消补偿；FINAL：最后组合、R22结项及远程CI。
- 外部 OQ-03、AC-26现场黑盒、真实WCS、容量/RTO/RPO和生产历史时间不能伪造。

## 当前问题

- 唯一后端工作树 /Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate，分支fix/serial-outbound-execution，SHIP已提交1dd3d18，正在整合main4812941；未发布。
- 定向数据库验收包括原身份分次发运、回执丢失/最后本地写失败、错证明、审计回滚、租约接管旧回执；其中登记端口故障夹具不称真实网络证明。
- 不在Maven运行中修改源码；输出重定向.local，只读结果/首个相关错误；同类失败两次复核原因。
- 原根控制台分支和文档分支保持不动，不从真实.env获取测试凭据。

## 下一步建议

1. 暂存核对OUT，契约生成一致性与文档注释检查后提交；整合最新main，保留已完善文档，更新迁移54表等事实。
2. 已通过证据可复用；集成导致相关变化再补测。真实XXL执行器的admin为协议夹具；生产及完整TC仍未验收。
3. 同步实际变更文档与必需IT清单，整合main并正常提交发布该片，再继续其余有限验收。

## 恢复 Prompt

先读本文件和唯一计划，核对git status与正在运行的Maven。从OUT未完成继续，不重做已发布入库/盘点，不丢弃SHIP，不把部分成功当全部整改完成，不等待逐片“继续”。
