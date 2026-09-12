# Codex Progress

## 任务目标

当前优先任务：按用户要求调用 project-documentation 完善项目文档，按已发布 main 核对架构、版本、连接、数据/API、运行验证与交付状态；完成检查后按持续授权提交并正常推送远程 main。不改变业务代码，不操作共享环境，不部署生产。

原后端目标保留：按收货分批继续 R13/R14/R15/R22；本次文档任务不视为剩余整改完成。用户转向文档后暂停后端扩展，没有授权子 Agent。

## 已完成

- 已发布业务基线 main `3e2c7209dba79af09cbb7d5678dc39943cc28957`；main CI `34721632607` success。
- 已发布普通消息、多 Cell 路由、真实 TM/TC/原生 RM 与出库授权、序列号分批收货/质检/上架/源释放、完整身份盘点/逐身份恢复/占用保护；详情见 `docs/delivery/wms-v1/DELIVERY_STATUS.md`。
- 文档工作树：`/Users/liruijun/personal/LLM/wms-platform/.local/project-documentation`，任务分支 `feat/project-documentation`，从上述 main 基线建立；保留根工作区与后端未发布改动。
- 已补充文档入口、连接清单和数据/API索引，校正架构/版本/运行手册/交付状态，标识历史证据及现场未核验项。
- 本地检查通过：文档53份/仓库链接231/AC50/唯一任务64；契约88路径且生成物无差异；Compose模板静态解析；git diff --check。另核对10个Maven模块、锁文件版本、各模块最高迁移、52表迁移清单和109项必需IT。没有运行Maven、npm业务测试或启动环境。

## 已修改文件

- `README.md`、`docs/README.md`、`docs/operations/INFRASTRUCTURE.md`、`docs/implementation/DATA_AND_CONTRACTS.md`。
- `docs/design/01-architecture.md`、`docs/design/07-decisions-evidence.md`。
- `deploy/README.md`、`docs/implementation/S0_RUNBOOK.md`、`docs/implementation/VERSION_LOCK.md`、`docs/implementation/WAREHOUSE_MIGRATION_LIMITS.md`、`docs/implementation/FULFILLMENT_EXECUTION.md`、`docs/implementation/COUNT_SERIAL_RECOVERY.md`。
- `docs/delivery/wms-v1/DELIVERY_STATUS.md`、`DELIVERY_PLAN.md`、`DELIVERY_REPORT.md`、`BACKEND_REMEDIATION.md` 与本文件。

## 未完成

- 当前文档：本地检查已通过；提交后合并新远程 main c5c96e3 的控制台更新，处理状态文档重叠，复查并正常发布。纯文档提交按既有规则使用 [skip ci]，不触发新 verify，不取消正在运行的控制台基线 CI 34723887946。
- 后端 R13：序列号 PICK/SHIP 完整链路及可信水位。PICK 本地提交 `dd22cd01934953ba16c9e95dd584a56be5604d12`，尚未发布；SHIP 有未验证改动。
- 后端 R14：公开序列调拨接入、TC 终态通知与原资源/Fence 迁移、全局提交后取消补偿。
- 后端 R15：上述路径恢复接线；归档仅候选，保留期限/删除/导出未批准。
- 后端 R22：已发布时间实现和 CI 通过；剩余整体整改完成后再复验结项，未核验/转换生产历史数据。
- 外部验收：OQ-03、真实 WCS、容量/RTO/RPO、全部 50 AC；不能编造输入。单桶 SN 观察上限 200，大桶分段协议尚未实现。

## 当前问题

- 后端独立工作树 `/Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate`，分支 `fix/serial-outbound-execution`。该树内旧进度可能落后实际 HEAD，恢复必须先核对 git status 与提交，不按旧记录重做盘点切片。
- 本地 dd22cd0 的序列 PICK 已有阶段验证，不能计入 main 文档的已交付；SHIP 未提交工作包括 registry V005、outbound V018、inventory V042 与相关 service/mapper/controller，尚未编译/测试，不能发布。
- SHIP 仍缺可靠 worker/client、可发运查询/凭证接线及故障测试。恢复时逐文件核对真实差异；不要在构建运行时编辑源码，不重启已通过的全部验证。
- 可信水位当前仍来自调用者字符串，不是来源关闭/库存过账/回执的实际证明。
- 环境模板需 OIDC issuer/client ID 与 `WMS_SERIAL_ALLOWED_SUBJECTS`；`up.sh` 自定义端口等待未加载 `.env` 中端口值，文档已记录，脚本未改。
- 本次未读取真实 `.env` 或凭据，未连接数据库/登录现场。既有 OSV 快照两个组件命中不等于本次重新扫描或零漏洞。

## 下一步建议

1. 完成本次文档检查与 Git 收尾；默认纯文档检查，不新增平台或启动业务环境。
2. 用户恢复后端工作时，读取上述后端工作树的实际差异和 `SERIAL_OUTBOUND_DESIGN.md`，先收敛 SHIP 的原 epoch 历史凭证与持久恢复，再做受影响真实数据库/进程验证。
3. 按当前批准范围继续可信水位、公开序列调拨、TC 资源迁移及取消补偿；每个完整逻辑单元通过必要验证后提交发布，不把一项通过当整体完成。

## 恢复 Prompt

请先读取 `CODEX_PROGRESS.md` 和 `docs/delivery/wms-v1/DELIVERY_STATUS.md`，核对当前任务及实际 Git 状态。当前优先完成文档分支检查/提交/合入远程 main；若文档已发布则不要重复执行。恢复后端时进入记录的后端独立工作树，保留未提交 SHIP 改动，从未完成范围继续，不重做已发布盘点与入库切片，不等我反复说“继续”，不运行共享环境故障或生产部署。
