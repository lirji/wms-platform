# Codex Progress — WMS 全模块包结构/格式/代码规范优化 IN_PROGRESS

## 任务目标

权限接入之后按Claude SKILL细化项目每个模块的包结构、格式化、按规范优化并完整验证/正常Git交付。当前默认WMS，异步范围问题可纠正；完整目标未完成，不把只格式化当作完成。

## 已完成

- 读取Claude project-refactoring/backend-implementation/dev-standards，同Codex/Claude/ Cursor规范摘要一致。
- 起点WMS远端main2efa151/完整CI37257041197 SUCCESS；当前全reactor package及93单测PASS；基线/测试保护Gate PASS。
- 复用既有干净central-authorization工作树，建立refactor/module-packages-code-quality。518 Java/120 SQL/IAM摘要落盘，原WMS17048d5/55文件/Driver/历史pilot工作保持。
- 正式报告与R00–R12全部范围计划落盘docs/refactoring/module-packages/。R00/R01 DONE（f6ceb72），R07包迁移本地验证DONE，R08开始。选择固定GoogleJavaFormat1.37.0及Prettier3.9.9开发工具，不新增后端运行依赖。

## 已修改文件

- docs/refactoring/module-packages/PROJECT_REFACTORING_REPORT.md、PLAN_METADATA.json。
- scripts/format-java.py、.editorconfig、wms-console格式配置及package/lock（精确版本已锁定）。
- 本文件；.local/refactoring-module-packages/为忽略的基线/日志/后续证据。

## 最新切片

- R07：16类迁移，本地全reactor clean test/94单测与SQL/Mapper/目录/格式守卫PASS。完整IT/CI/卫生终审尚未执行；继续R08。

## 未完成

- R01格式化/编译及93Java、86前端、10脚本测试PASS；继续R02–R12覆盖全部正式模块、代码规范/优化/全量验证/正常main交付。
- 原未提交Driver独立工作只能只读核对，不能混入当前任务Git；若需改动，先确定其范围/工作归属。

## 当前问题

- 导航解析格式回归已修复，原失败保留。规范化基线的未修改技能卫生引擎无阻断，仅命令发现FORMAT_TOOL_NOT_AVAILABLE限制；实际格式check全部PASS，见R01_TEST_RESULT.json。

- 本任务没有重部署授权；现有W07制品和数据保持。SQL迁移/权限与HTTP/事件契约不得因拆包改变。
- 原WMSlocal main17048d5和55文件保护；不清理11旧工作树/镜像/卷/测试数据/证据，不强推。

## 下一步建议

1. R01已验证提交f6ceb72；按R02_PACKAGE_MAPPING开始契约与WCS包迁移，再连续后续切片。
2. 每批通过编译/相关真实测试/namespace检查/diff才继续；更新本文件，不等待继续。最终完整CI与兼容门禁再发布main。

## 恢复 Prompt

读取本文件及docs/refactoring/module-packages报告/PLAN_METADATA、.local/refactoring-module-packages当前日志和git状态，从R01/后续未完成切片继续。目标是每个模块的包结构+格式+规范优化全完成，不止格式化；保留原WMS和Driver未提交工作，不重做已完成Auth权限，不修改运行环境，不等待继续。

---

## 以下为已完成权限接入及历史上下文

# Codex Progress

## 任务目标

完成W00–W07 WMS接入Auth：独立local-wms绑定ENT-DEMO，真实本机运行和正常Git发布，不生产部署。

## 已完成

- W00–W06实施/验证/正常main发布；Auth dd04404/CI37249142702、WMS0f62d41/CI37249068115全成功。原W04失败保存，由累计全CI解除发布门禁。
- W07四源码镜像、HTTPS18545/5独立UID10001/600卷，隔离18、实际旧IdP/机器Owner/SQL/旧序列同库重放23、真实Auth管理范围/严格撤权/连续投影证明完成。
- 原6WMS应用与治理console/admin/projector、WMS relay/server共11服务healthy；原目标4用户PKCE6项/5当前图片实看、最终11 SDK/故障/恢复PASS，最终浏览器0个5xx/0 mock/0 Token注入/0业务写。
- 原两MySQL镜像/业务卷不变，最终142表摘要相同；初始化挂载路径变但文件字节同。原dirty WMS HEAD/status/55文件同，Driver/ERP/local main保护。
- Runtime/验收/审查/连接记录同步；当前仅本任务私密堆64–256MiB，同一Graph本体转发、Java客户端仍回环；原IdP/Graph实例不变，治理console只更新挂载、静态镜像不变。原超时/502/工具失败保留。

## 已修改文件

- Auth W07 Dockerfile/Compose、relay/init/Graph默认覆盖、四工具、CI、README/doc-map及WMS设计Runtime/W07结果/审查/状态。
- WMS 中央Compose、真实浏览器工具/CI、docs/iam/deploy README及本文件；无W07 Java/UI产品源码变更。

## 未完成

- 实施与本机验收无剩余项；最新精确提交Git/CI/远端main事实按Auth私密delivery-result.json核对并连续完成，不重复实施或部署。

## 当前问题

- 生产/容量/灾备/TCC业务与全目标实际回滚不在本片证明范围。机器JWT一小时/演示Grant24小时，显式续发；无自动续发。凭据在Auth0600 w03-state/ACCESS.md。
- 自有fixture/测试库已正常停止，数据/容器/卷/网络/旧镜像/备份/失败/工作树保留；只结束自己有界caffeinate，禁止清理他人内容。
- 原WMS local main保持17048d5，远端main另按已验收SHA发布，不能修改原dirty树来同步。Graph重建需重新核对连接绑定，不能照用历史IP。

## 下一步建议

1. 核对最新delivery-result.json及精确CI终态，按授权正常发布main；有失败保留并修复/复核，不绕过。
2. 当前源/运行已完整验收，不重复灌数、授予、重建或恢复Docker。保留测试证据和任务树。

## 恢复 Prompt

读本检查点、Auth规范PROGRESS_STATE和私密delivery-result.json，从尚未完成的Git/CI交付继续。W00–W07实施及原本机验证完整PASS，原WMS/管理已实际更新，142表及55文件保护PASS；最新原目标浏览器original-target-browser-final6项零5xx。不要重做部署或授权、不清理、不改原dirty树，不等待继续。

---

## 以下为历史任务记录

# Codex Progress

## 当前任务：前端修复本机 Docker 部署（2026-10-03）

### 任务目标

按用户后续授权，将 Claude 技能试点中验证的入库创建回执/失败恢复和应收数量列标题修复部署到 desktop-linux 本机 wms-local，保留原项目数据与无关工作。

### 已完成

- 复用现有任务 worktree，分支 fix/frontend-inbound-recovery-docker；前端源码分别提交 09f03e4、8e5dbe0。原项目 main 的 Driver/pom/文档/进度等用户改动保留。
- 同版前端 15 项隔离浏览器、36 文件74项测试、类型/build 检查及远程 console CI 37109208184 通过。共享 Claude/Codex/Cursor 前端技能无需内容修补，原验收报告保留历史范围。
- 核对222项本机已安装依赖与锁文件一致，使用真实公开 OIDC 参数编译并冻结43项静态资源。引擎停滞期间取消原容器编译；采用本机 tsc/Vite 编译及相同 Nginx digest 打包，没有修改永久 Dockerfile。
- 用户授权重启 Docker Desktop。正常停止超时后对核实的 Docker 父进程发送 SIGTERM，再正常 start；现有镜像与数据卷保留。恢复了同次重启退出255、未自动恢复的4个原 auth-platform 容器。
- 实际发布 console 镜像 sha256:4b80def5429ce60a59b1fba7092fd6cb298969cf1d2dc83d9db221372fac90c0，OCI revision 为8e5dbe0c262b93d286ab8aae2ff270feb251c626。控制台、5个后端与5个必要中间件全部 healthy，后端镜像保持原身份。
- 56 项真实 HTTP 检查通过：入口/深链接/readiness/匿名业务401/OIDC discovery/43静态资源哈希；4项新上下文真实浏览器检查通过并已查看截图，登录按钮进入原 Casdoor 表单。没有模拟响应或注入身份。
- 新增本机发布叠加配置：不可变 console image及 inbound/outbound/serial 的10秒连接预算；根拓扑、凭据、端口及数据归属沿用现状。部署说明同步，Compose配置/diff/文档结构检查通过。

### 已修改文件

- 本任务10个前端源/测试文件，见提交09f03e4、8e5dbe0。
- deploy/compose.console-release.yml、deploy/README.md、本文件。

### 未完成与验证限制

- 当前 Docker 部署目标已完成；收尾按已授权的正常Git流程发布本任务到 origin/main，并将真实结果保存到下述外部报告。此记录随发布提交保存，不预先宣称 push 成功。
- 整套 Java CI仍按实际远程状态记录，不能以 console job 成功宣称全仓CI通过；没有在本轮修改后端源码。
- 未完成真实账号登录后的授权/创建/持久化验收，也未覆盖生产、真实用户观察或所有50项 AC。历史业务待办保持。

### 当前问题与回退

- 无阻止本机控制台部署的未解决故障。保留引擎停滞、初次等待失败和修复证据，不以最终成功覆盖失败历史。
- 旧 console 镜像 sha256:26d410a7428111b462083cc2d09e2494c7e4ec6292e30b40b1a314dd445e5f94 保留；按 deploy/README.md 单服务回退。本轮 NOT_NEEDED，无删卷/清空数据。

### 下一步与恢复 Prompt

入口 http://127.0.0.1:18180/ 。最新实际 Git/CI/部署结果以 /Users/liruijun/outputs/wms-console-docker-20261003/REPORT.md 及 evidence/ 为准。恢复时先读取报告和当前 Git/Docker 状态，仅处理已记录收尾，不重新构建已部署镜像、不启动 S9/Driver 积压任务。任务 worktree 与冻结证据先保留，未经清理授权不删除。

## 以下为任务起点的 WMS 历史上下文

## 任务目标

完成已批准 WMS v1 到 S9 与 50 项 AC。有限后端范围 OUT → WATERMARK → TRANSFER → TC → COMP → FINAL 已发布。控制台按已公开契约接线序列拣发/调拨、对账窗口、仓级 action-effects，以及 PDA 收/拣/发。

本轮另交付：本地可运行的全链路测试数据工具 `test-data/`。控制台列表空列按公开契约字段补齐（入库、库存、履约、出库、调拨、盘点、批次）。详情页按各 GET 本域字段展示，不再套入库实物/库存同步头。

## 已完成

- OUT `365a1eb`、WATERMARK `051a7eb`、TRANSFER `7659d34`、TC `a3b4c65`、COMP `f043117` 均在远程 main；COMP CI [34734659069](https://github.com/lirji/wms-platform/actions/runs/34734659069) 成功。
- 控制台公开契约 `a619d3f`、FINAL 回执 `c07e9b0`、PDA 拣/发 `785c7a2`、库存-only 202 轮询 `fcc8c09` 已授权合入远程 main。
- `test-data/` 一键初始化/清理/验证（2026-09-13）：复用官方 Seed* 入口类并写入 `TESTFL-` 补充场景。已按第 3/4 节约束修正 Compose 优先、数量按场景最小集，init 结束打印实测 COUNT。本机隔离库 DATA_VERIFY 16 项 PASS；应用 4/4 存活但无 JWT，APP_VERIFY SKIPPED。
- 入库列表 / 库存台账空列（2026-09-13）：`listOrdersPage` 附带首行 `sku_id`/`expected_qty`/`received_physical_qty`/`stock_sync_status`；控制台 `field()` 可读 snake_case；库存台账展示契约字段「执行占用」，不发明 `availableQty`。`InboundHttpIT` 与 console 单测已绿。
- 履约/出库/调拨/盘点/批次空列（2026-09-13）：履约/出库/调拨列表同样附带首行 SKU 与数量；盘点列表展示 `reason_code` + 首个范围库位，不编造草稿 SKU；`LOT-STD` 无时刻显示「未绑定」。`FulfillmentHttpIT` / `OutboundHttpIT` / `MasterdataHttpIT` 与对应 console 单测已绿。
- 详情页本域字段（2026-09-13）：`DocumentWorkbench` 去掉公共「实物 / 库存同步」头；入库/履约/出库/调拨/盘点各自传入 GET 已有字段。出库 `orderView`、调拨 `view`、盘点 `view` 补 `version`；盘点详情带回 `locations`（已有 `listScope`）。批次空时刻详情也显示「未绑定」。不发明可用量或盘点 SKU。任务提交 `cb7dac9`，按用户授权直接推远程 main，不走 verify。

## 未完成

- 取消补偿查询（无公开 GET）、库位门禁写（无公开写）。
- OQ-03 / AC-26 现场 / S8-05 / S9-01 / 50 AC。
- Cell B `wms_inventory` 已有表但缺少时区来源记录；官方 SeedLocal fail-closed。未编造 `legacy-evidence`，WH-B 库存种子 BLOCKED。根 Compose inventory 只接 Cell A。
- 本机 Docker `console` / `outbound` / `fulfillment` / `inventory` 已按本次详情修复重建。硬刷新详情页即可。

## 下一步

1. 硬刷新本机 `http://127.0.0.1:18180` 的入库/履约/出库/调拨/盘点/批次详情。
2. 不发明补偿 GET、门禁写、OQ-03。不把模拟器当设备。不在浏览器算可用量。

## 当前工作树

- `/Users/liruijun/personal/LLM/wms-platform/.local/console-public-serial-ops` @ `feat/console-public-serial-ops`
- 根用户工作树在 `feat/console-contract-list-detail` @ `cb7dac9`。

## 恢复 Prompt

读取本文件。公开契约前端已授权发布。不要发明 OQ-03 或内部 HTTP。测试数据只改 `test-data/**`。
