# Codex Progress

## 任务目标

按已批准计划做到 S9 与 50 项 AC。S7-03…S7-05 已在 remote main `38a7f6a`。S8-01 本地已通过。未发明 OQ-03。S8-04 前不创建 `wms-console/`。

## 已完成

- S0、S4-01…S7-05 已发布 `38a7f6a`。
- S8-01 数量事实快照本地 IT 通过。

## 已修改文件（本轮）

- `V019__reconciliation_snapshot.sql` / `WarehouseQuantityFact` / `SnapshotExportService` / `SnapshotExportController` / `SnapshotExportIT`

## 未完成

- 等 main verify `34668676522` 后发布 S8-01。
- S8-02 起需独立 recon-platform 评审。S8-04 才做 console。S9 与 50 AC。

## 下一步建议

1. 提交推送 `feat/wms-s8-01`；`34668676522` 成功后再 merge main。
2. 开始 S8-02 只读评审 recon-platform，不猜写入。不要把目标缩成只做快照。

## 恢复 Prompt

读取 CODEX_PROGRESS.md。从第一个未完成切片继续。不要发明 OQ-03。S8-04 前不要创建 wms-console。不要改未评审的 recon-platform。
