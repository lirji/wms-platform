# Codex Progress — Auth 与 WMS 重构验收及交付检查点

## 任务目标

权限接入完成后，按Claude SKILL整理Auth与WMS正式模块的包结构、统一格式、规范优化、完整验证并正常Git交付。两仓最终main门禁通过才关闭整体goal。本轮不部署或续期。

## 已完成

- WMS R00–R12源码实施与候选验收完成：419类迁移、765公开说明、共享纯规则/预算与数据库时间边界整理；WCS去重/并发及上下文旧错误修复均先失败后通过。
- WMS a1df348/CI37275190238完整SUCCESS：去重100单测/230真实IT，0失败/错误/跳过；默认213、warehouse12、TC2、failure3，必需147/3与启动smoke通过；87console/14脚本及类型/build/格式/契约PASS。
- 120SQL/64Mapper SQL/2权限目录/OpenAPI字节、419映射、可见性及936源码/验证输入指纹核对PASS；全任务卫生0阻断，262144命名预算1建议已审查，Java格式发现限制有实际check补证。
- Auth A00–A08完整DONE，main e4d14eb1b764f53419bbda9ee42920d9b0d3920b与精确Auth37276514965/Portal37276514926均成功；332单测/325真实IT/1Boot4、另验legacy1/React2，170公开类型兼容，39SQL/6图/2运行配置不变。
- 初次进程退出原因未知、过期清单包名、Auth CI env归属和首次legacy超时均已保留原始失败及修复/重验记录，不删除用例或放宽断言。

## 已修改文件

- 各正式模块src、消费者/namespace/自动配置、固定格式与结构守卫/CI、docs/refactoring/module-packages及本文件，按逻辑单元提交。
- 用户原Driver、旧试点未提交内容、私密配置/证据与既有运行制品不混入本次提交。

## 未完成

- Git及最终main门禁的实时事实在.local/refactoring-module-packages/DELIVERY_RESULT.json和R12_TEST_RESULT.json；overall/status为DONE时无剩余实施或交付事项。
- 若回执仍标MAIN_PUSHED_CI_IN_PROGRESS，只等待已记录的精确main CI到终态，失败则保存归档并按原因有界修复；不能以候选成功代替main证据。

## 当前问题

- 原WMS main已正常快进2efa151，checkpoint与早期快照不同，其余54文件相同；当前55文件摘要已重新捕获并保护。只使用当前既有任务树，不覆盖原脏目录或移动其本地main。
- 11旧工作树、私密凭据/预算/备份/失败保留。原Driver和旧试点有未提交内容，当前树供运行挂载；无清理/部署/自动续期。可重建target/缓存不承担权威数据，本轮不删除。
- Java格式CLI自动发现限制已在报告列明，实际固定格式check均通过；容量、真实设备、生产与旧业务待办不由本次测试替代。

## 下一步建议

1. 先读取私密DELIVERY_RESULT、R12_TEST_RESULT和Auth对应回执，核对已记录的main SHA及CI。若两仓DONE，则任务结束，不重复实施/测试/发布或等待继续。
2. 若尚有main CI进行中，继续同一run；不存在私密回执时从远端main与对应精确流水线核对，不重跑历史包迁移脚本。
3. 保留用户当前工作、旧树和运行数据，不部署、不续期、不清理；只有两仓实际交付完整才将goal标complete。

## 恢复 Prompt

读取本检查点与私密DELIVERY_RESULT/R12_TEST_RESULT的最新实际状态。Auth完整DONE；WMS候选完整CI/所有profile/兼容卫生已PASS，只继续回执中尚未完成的精确main交付。若main全成功则结束，不重做包迁移、Auth发布或权限部署，不清理或覆盖用户内容。

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
