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
