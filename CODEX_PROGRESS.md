# Codex Progress

## 任务目标

当前文档任务已完成：按用户要求调用 project-documentation 核对并完善架构、版本、连接、数据/API、运行验证与交付状态，通过必要检查并按持续授权正常发布远程 main。不改变业务代码，不操作共享环境，不部署生产。

原后端目标保留：按收货分批继续 R13/R14/R15/R22；本次文档任务不视为剩余整改完成。用户转向文档后暂停后端扩展，没有授权子 Agent。

## 已完成

- 2026-09-13：两份后台 UI 强约束融合为 `docs/design/console-frontend/ADMIN_UI_PROMPT.md`，架构文与 `console-admin-ui` 规则指向它。后续建页先套该提示词；`WARNING` 映射橙色等待，不增加第六种 Tag 色。契约没有的模板不发明。
- 2026-09-13：按后台 UI 强约束改已有控制台：浅色侧栏、`#1677FF` 主色、Default 白底灰边、五色状态 Tag、列表查询区+表底右齐游标翻页。不编页码，不扩新作业能力。
- 2026-09-13：商品/库位/批次补公开 GET 详情页；列表点标识进入。SKU 展示策略与单位，库位展示容量与只读门禁，批次展示货主/效期。不发明写门禁。
- 2026-09-13：表格下方「首页 / 下一页」改为 `ListPager`：描边蓝 / 有下一页则实心蓝，禁用保留浅蓝，不再是浅灰胶囊。入库批次翻页同一套。
- 2026-09-13：控制台落地 shadcn 克制壳 + 工业实心状态色；命令居中弹层。列表工具条拉开主次：一颗实心主按钮，其余加粗描边；查询元数据不再挤在按钮行。
- 2026-09-13：按当前 main `4812941` 在 Docker 重建 `wms-local` 五个后端与控制台（项目改挂根 `compose.yaml`，保留数据卷）。旧卷缺 `wms_registry` 已初始化账号；四业务库按时区门禁补了已核对的 UTC `legacy-evidence`。readiness 与控制台 `/` `/login` 均 200；镜像含 `serialIds` 资源。健康 UP 不是 50 AC / AC-26 accepted。未操作共享 dev-infra，未 `--volumes`。
- 当前业务源码基线 main `c5c96e3aa4cf0ba58dbfab863fc8c797f9db1387`。后端仍为 `3e2c720` 的相同源码/依赖，其 CI `34721632607` success；最新控制台基线 CI `34723887946` 的 console 已成功、java 运行中；文档推送后该运行未被取消。
- 控制台 `b6f44ac` 已发布：序列号观察、202 保留原键/有界轮询、单据双状态、401 去登录、序列恢复/消息重排、履约 attempt 执行、商品/库位/批次三表、单位写入及门禁只读。既有本地 21 文件/41 用例/typecheck/build 通过；本次未重跑。公开拣/发/调拨序列号字段仍缺，页面未发明。
- 已发布普通消息、多 Cell 路由、真实 TM/TC/原生 RM 与出库授权、序列号分批收货/质检/上架/源释放、完整身份盘点/逐身份恢复/占用保护；详情见 `docs/delivery/wms-v1/DELIVERY_STATUS.md`。
- 文档工作树：`/Users/liruijun/personal/LLM/wms-platform/.local/project-documentation`，任务分支 `feat/project-documentation`，从后端 main 3e2c720 基线建立，随后整合已发布控制台 c5c96e3；保留根工作区与后端未发布改动。
- 已完成17份文档补充/修订，主体 c32cb54 与整合 f0b2c81 已发布远程 main；最新控制台代码和两份前端文档完整保留。纯文档 [skip ci] 未创建新 verify，原 CI 继续运行。
- 本地检查通过：文档53份/仓库链接234/AC50/唯一任务64（已整合控制台文档）；契约88路径且生成物无差异；Compose模板静态解析；git diff --check。另核对10个Maven模块、锁文件版本、各模块最高迁移、52表迁移清单和109项必需IT。没有运行Maven、npm业务测试或启动环境。

## 已修改文件

- `README.md`、`docs/README.md`、`docs/operations/INFRASTRUCTURE.md`、`docs/implementation/DATA_AND_CONTRACTS.md`。
- `docs/design/01-architecture.md`、`docs/design/07-decisions-evidence.md`。
- `deploy/README.md`、`docs/implementation/S0_RUNBOOK.md`、`docs/implementation/VERSION_LOCK.md`、`docs/implementation/WAREHOUSE_MIGRATION_LIMITS.md`、`docs/implementation/FULFILLMENT_EXECUTION.md`、`docs/implementation/COUNT_SERIAL_RECOVERY.md`。
- `docs/delivery/wms-v1/DELIVERY_STATUS.md`、`DELIVERY_PLAN.md`、`DELIVERY_REPORT.md`、`BACKEND_REMEDIATION.md` 与本文件。

## 未完成

- 当前文档无剩余实施项。主体 c32cb54、整合提交 f0b2c81 已推任务分支与远程 main，ls-remote 已核对两者包含该提交；本次进度回写随后作为纯文档提交发布。未运行新的业务 CI，不把已有 java 运行中标为通过。
- 后端 R13：序列号 PICK/SHIP 完整链路及可信水位。PICK 本地提交 `dd22cd01934953ba16c9e95dd584a56be5604d12`，尚未发布；SHIP 有未验证改动。
- 后端 R14：公开序列调拨接入、TC 终态通知与原资源/Fence 迁移、全局提交后取消补偿。
- 后端 R15：上述路径恢复接线；归档仅候选，保留期限/删除/导出未批准。
- 后端 R22：已发布时间实现和 CI 通过；剩余整体整改完成后再复验结项，未核验/转换生产历史数据。
- 控制台 AC-26 现场黑盒仍 open：序列号收货、202 原键、401/429、对账审批与断网重连；需获授权 Casdoor/隔离栈，后续公开拣/发契约完成再补 UI。
- 外部验收：OQ-03、真实 WCS、容量/RTO/RPO、全部 50 AC；不能编造输入。单桶 SN 观察上限 200，大桶分段协议尚未实现。

## 当前问题

- 根用户工作区 `/Users/liruijun/personal/LLM/wms-platform` 当前为 `main`（`4812941`）。不要切到 `.local/backend-remediation-integrate`。现场 `.env` 在根目录（gitignore），口令不进仓库。

- 后端独立工作树 `/Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate`，分支 `fix/serial-outbound-execution`。该树内旧进度可能落后实际 HEAD，恢复必须先核对 git status 与提交，不按旧记录重做盘点切片。
- 本地 dd22cd0 的序列 PICK 已有阶段验证，不能计入 main 文档的已交付；SHIP 未提交工作包括 registry V005、outbound V018、inventory V042 与相关 service/mapper/controller，尚未编译/测试，不能发布。
- SHIP 仍缺可靠 worker/client、可发运查询/凭证接线及故障测试。恢复时逐文件核对真实差异；不要在构建运行时编辑源码，不重启已通过的全部验证。
- 可信水位当前仍来自调用者字符串，不是来源关闭/库存过账/回执的实际证明。
- 环境模板需 OIDC issuer/client ID 与 `WMS_SERIAL_ALLOWED_SUBJECTS`；`up.sh` 自定义端口等待未加载 `.env` 中端口值，文档已记录，脚本未改。
- 本次未读取真实 `.env` 或凭据，未连接数据库/登录现场。既有 OSV 快照两个组件命中不等于本次重新扫描或零漏洞。

## 下一步建议

1. 文档已交付，阅读入口 `docs/README.md`；按需查看既有控制台 CI 34723887946 后续结果，不重跑文档任务或启动业务环境。
2. 用户恢复后端工作时，读取上述后端工作树的实际差异和 `SERIAL_OUTBOUND_DESIGN.md`，先收敛 SHIP 的原 epoch 历史凭证与持久恢复，再做受影响真实数据库/进程验证。
3. 按当前批准范围继续可信水位、公开序列调拨、TC 资源迁移及取消补偿；每个完整逻辑单元通过必要验证后提交发布，不把一项通过当整体完成。

## 恢复 Prompt

请先读取 `CODEX_PROGRESS.md` 和 `docs/delivery/wms-v1/DELIVERY_STATUS.md`，核对当前任务及实际 Git 状态。文档主体 f0b2c81 已在远程 main，不要重复执行文档任务。恢复后端时进入记录的后端独立工作树，保留未提交 SHIP 改动，从未完成范围继续，不重做已发布盘点与入库切片，不等我反复说“继续”，不运行共享环境故障或生产部署。
