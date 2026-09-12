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

2026-09-12 F5 已把交接要求的写命令接到工作台。同日 Casdoor 现场走完收货→上架→准备跨仓→拣→部分发→取消回库，attempt 非 ALLOCATED；AC-26 仍 open。F6 把建单和作业命令收进抽屉，首页改为活队列，401 不再写成权限不足；Casdoor `wms-ops` / `wms-wh-a` / `wms-wh-b` 已补齐 OpenAPI 作业 scope（42 项）。S8-05 / S9-01 保持 blocked。
