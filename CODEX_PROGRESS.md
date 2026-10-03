# Codex Progress

## 当前任务：前端修复本机 Docker 部署（2026-10-03）

- 用户已授权部署刚完成试点的前端修复，目标为 desktop-linux 本机 wms-local 控制台，沿用真实 OIDC/Compose/现有数据卷和后端镜像。
- 当前任务工作树：~/.local/share/git-worktrees/wms-platform/frontend-skill-pilot-20261003，分支 fix/frontend-inbound-recovery-docker，基线 7b79733；原 main 工作区的 Driver/文档/进度等无关修改保留，不夹带交付。
- 前端10个源/测试文件与试点最终身份一致；15项隔离浏览器、36文件74项测试、类型检查/build/diff检查已通过。B列标题与A创建失败恢复按独立逻辑提交。
- 本地部署门禁：相同源码的前端测试、实际Docker编译与镜像身份。Git main发布另核查本次远程console检查；历史基线Java CI失败记录，不宣称全仓CI已通过。
- 进行中：构建带真实公开OIDC参数的不可变前端镜像，启动现有必要依赖，验证HTTP/SPA/反代/健康及浏览器登录入口。
- 尚未完成：镜像/服务部署、实际访问smoke、正常Git发布与最终进度保存。无需新增中间件拓扑或修改正式后端/真实业务数据。
- 回退：保留部署前console镜像sha256:26d410a7428111b462083cc2d09e2494c7e4ec6292e30b40b1a314dd445e5f94；若新console健康失败，用单服务image覆盖回退，不删卷、不停止其他项目。
- 证据与最终部署记录：/Users/liruijun/outputs/wms-console-docker-20261003/。普通启停仍用根compose.yaml/deploy脚本。
- 恢复：先读取上述本轮证据和实际Docker/Git状态，完成未完成动作；下列WMS历史业务目标与待办保持，不自动启动S9或Driver积压工作。

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
