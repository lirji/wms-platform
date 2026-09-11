# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC。切片完成后自动下一片，不要等「继续」。当前切片 S4-02。未发明 OQ-03。未到计划 S8 前不创建 `wms-console/`。

## 已完成

- S0 XXL/SBOM 与 S4-01 已在 remote main `c494f66`。
- 工作树 `.local/s1-masterdata` 分支 `feat/wms-s4-02`。
- 持久规则 `~/.cursor/rules/continue-approved-delivery.mdc`：切片结束后立刻下一片。

## 已修改文件（本轮）

- `V002__allocation_digest.sql`、`AllocationPlan`、`TryPropagation`、`FulfillmentService` 冻结数量/有界Try/显式XID头、`FulfillmentPlanIT`。

## 未完成

- S4-02 Git 发布。S4-03→S9。50 项 AC。OQ-03。S8 才做 `wms-console/`。

## 下一步建议

1. 发布 S4-02 后立刻 S4-03 `ReservationTccAction`。
2. 不要等用户说继续。不要把目标缩成只做 S4。

## 恢复 Prompt

读取 CODEX_PROGRESS.md。从第一个未完成切片继续，不要要求反复输入继续。不要发明 OQ-03。未到 S8 不要创建 wms-console。
