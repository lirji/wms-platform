# Codex Progress

## 任务目标

按已批准的唯一计划`docs/delivery/wms-v1/DELIVERY_PLAN.md`连续推进 WMS，不需用户再说继续。当前切片 S2-03 库存原语（进行中）。不能宣称 50 项 AC 完成。未开始`wms-console/`。

## 已完成

- S1-06 `20dc6a7`、S2-01 `3e90882`、S2-02 `5a7b9fe` 已快进远程 main。
- 工作树`/Users/liruijun/personal/LLM/wms-platform/.local/s1-masterdata` 当前分支`feat/wms-s2-03`。

## 已修改文件（S2-03 未发布 main）

- `InventoryApplicationService`：收货、Try 预占、TCC Cancel；同 operation 不二次加量；先锁门禁。
- `InventoryApplicationIT` 2 项本地通过。
- 尚未实现移动、发运、Outbox。

## 未完成

- S2-03 其余原语与 Outbox。S2-04 发布器。S2-04a command/permit。S2-05 并发 IT。
- 隔离 compose Casdoor JWT。50 项 AC。OQ-03。`wms-console/`。
- S1-06 远程 CI #34494563824 被 main 并发取消；S2-02 对应远程 CI 待核验。

## 下一步建议

1. 在 `feat/wms-s2-03` 补 move/ship，再与 Outbox 表同事务后才快进 main。
2. 不要把未完成的 S2-03 当作 AC-03 通过。

## 恢复 Prompt

请读取CODEX_PROGRESS.md，核对 Git。从 feat/wms-s2-03 未完成的 move/ship/Outbox 继续。只操作隔离工作树。不要要求反复输入继续。
