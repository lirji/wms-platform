# Codex Progress

## 任务目标

按已批准的唯一计划`docs/delivery/wms-v1/DELIVERY_PLAN.md`连续推进 WMS，不需用户再说继续。当前切片 S2-04a 库存命令/凭证/permit 与最小 T1/T2/T3（进行中）。不能宣称 50 项 AC 完成。未开始`wms-console/`。

## 已完成

- S2-03 `0bf8d14`、S2-04 `31cd3c3` 已快进远程 main。
- 工作树`/Users/liruijun/personal/LLM/wms-platform/.local/s1-masterdata` 当前分支`feat/wms-s2-04a`。
- S2-03 main CI #34541003086 被后续 main 快进 cancel-in-progress。S2-04 feat #34541582571 / main #34541603485 发布时仍在跑。

## 已修改文件（S2-04a 未发布 main）

- `V007__stock_command.sql`：`stock_command`/`stock_posting`/`execution_permit`/`execution_claim`。
- 表数断言 16→20。T2 受理、入出库 T1/T3 尚未写。

## 未完成

- S2-04a T2 命令受理/取消墓碑、inbound/outbound source_command/inbox、最小三服务闭环。
- S2-05 并发 IT。隔离 compose Casdoor JWT。50 项 AC。OQ-03。`wms-console/`。

## 下一步建议

1. 在 `feat/wms-s2-04a` 实现库存 T2 受理与取消墓碑，再补来源 T1/T3。
2. 未完成 T1/T2/T3 前不要快进 main。不要把表迁移当作 AC-05 通过。

## 恢复 Prompt

请读取CODEX_PROGRESS.md，核对 Git。从 feat/wms-s2-04a 未完成的 T2/T1/T3 继续。只操作隔离工作树。不要要求反复输入继续。
