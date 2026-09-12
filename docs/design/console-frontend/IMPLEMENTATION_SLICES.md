# 控制台前端切片

架构见 [FRONTEND_ARCHITECTURE.md](FRONTEND_ARCHITECTURE.md)。不另写交付计划。

| ID | Outcome | Needs | Parallel | Paths | AC (observable) | Pass | Runtime |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F0 | 架构文档可链到仓库 | — | no | `docs/design/console-frontend/` | `check-docs.py` 链接存在 | done 2026-09-12 | none |
| F1 | tokens、URL 选仓、typed 信封 | F0 | no | `src/design` `src/api` `src/shell` `src/app` | 无仓时跳到 `/w/:id`；测试绿 | done 2026-09-12 | none |
| F2 | 工作台 / 资料 / 库存绑活 API | F1 | no | `src/features/home|catalog|stock` | 空仓空态；数量字符串列 | done 2026-09-12 | live API |
| F3 | 入库履约调拨盘点任务对账列表 | F1 | no | `src/features/inbound|fulfillment|transfer|count|jobs|recon` | 列表来自契约路径，无 Mock 表 | done 2026-09-12 | live API |
| F4 | PDA 收货独立壳 | F1 | no | `src/features/pda` | 回车提交；202 文案非成功 | done 2026-09-12 | live API |

2026-09-12 视觉升级为企业侧栏作业台后，下一步仍是 AC-26 写作业链（收货→上架→跨仓→拣发）。S8-05 / S9-01 保持 blocked。
