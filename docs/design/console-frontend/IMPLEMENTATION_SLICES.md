# 控制台前端切片

架构见 [FRONTEND_ARCHITECTURE.md](FRONTEND_ARCHITECTURE.md)。不另写交付计划。

| ID | Outcome | Needs | Parallel | Paths | AC (observable) | Pass | Runtime |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F0 | 架构文档可链到仓库 | — | no | `docs/design/console-frontend/` | `check-docs.py` 链接存在 | done 2026-09-12 | none |
| F1 | tokens、URL 选仓、typed 信封 | F0 | no | `src/design` `src/api` `src/shell` `src/app` | 无仓时跳到 `/w/:id`；测试绿 | done 2026-09-12 | none |
| F2 | 工作台 / 资料 / 库存绑活 API | F1 | no | `src/features/home|catalog|stock` | 空仓空态；数量字符串列 | done 2026-09-12 | live API |
| F3 | 入库履约调拨盘点任务对账列表 | F1 | no | `src/features/inbound|fulfillment|transfer|count|jobs|recon` | 列表来自契约路径，无 Mock 表 | done 2026-09-12 | live API |
| F4 | PDA 收货独立壳 | F1 | no | `src/features/pda` | 回车提交；202 文案非成功 | done 2026-09-12 | live API |
| F5 | 作业命令接到已落地 HTTP | F3/F4 | no | `src/features/*` 详情页 + inbound/outbound/fulfillment/inventory 写接口 | 详情可提交收货/质检/上架/拣包发/取消/调拨/盘点/任务/对账；202 非成功 | done 2026-09-12 | live API；TCC ALLOCATED 仍需真实 TC |
| F6 | 队列/单据变短；令牌权限可见 | F5 | no | `Drawer` 建单与命令、首页活队列、Casdoor 作业 scope | 列表页不再内嵌长表单；401≠403；顶栏能看到仓与权限 | done 2026-09-12 | live API + Casdoor；需重新登录拿新 JWT |
| F7 | 按技能补齐密度与权限隐藏 | F6 | no | 登录一列、Ant token 单轨、列宽/复制 id、URL 筛选与 cursor、按 scope 隐藏命令、错误码对照、单据 Tabs | 登录不再两列营销墙；无 scope 的命令不画出；筛选进 URL；409 不 dump JSON | done 2026-09-12 | live API |
| F8 | 契约缺口 HTTP + 对应页面 | F7 | no | 主数据写/按 id 读、仓任务 list/get/claim、流水/操作/效果、catalog 建档、jobs 仓任务、stock 流水、recon 导出 | 有 domain 的公开缺口接到 HTTP；当时无 domain 的 moves/holds 不编造；页面不宣称 ALLOCATED | done 2026-09-12 | Testcontainers HTTP IT + console 测试；TP99 unverified |
| F9 | 补齐缺领域公开路径 | F8 | no | 同仓移库、stock-holds/releases、独立 adjustments、履约 cancellations、出库 execution-authorizations；库存台账与履约/出库详情命令 | 移库/限制/调整写库存域；202 取消≠TC 回滚；授权缺 Committed 证据 409；不发明履约 ALLOCATED | done 2026-09-12 | DomainHttpIT / FulfillmentHttpIT / OutboundHttpIT；console 33；列表调整无 L1+L2；TP99 unverified |

2026-09-12 技能复查：F0–F9 仍有效，不另起 IA。S8-05 / S9-01 保持 blocked。

2026-09-13：F-catalog 补 GET 详情页（sku / location+gate / lot），列表点标识进入；不发明写门禁或批次改写。
2026-09-13：已有页面按后台 UI 强约束换肤（浅栏、#1677FF、五色 Tag、查询区）；不扩新作业能力，游标分页不改编页码。
2026-09-13：两份 UI 强约束融合为 `docs/design/console-frontend/ADMIN_UI_PROMPT.md`，供后续创建后台页；`.cursor/rules/console-admin-ui.mdc` 指向该稿。
