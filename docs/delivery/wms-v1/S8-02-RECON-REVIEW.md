# S8-02 recon-platform 源码评审与落点

日期：2026-09-12。对象：本地 `recon-platform` HEAD=`ee3b070`。只读检查后写入本计划；实现落在独立工作树，不碰原目录未跟踪的 `.gitignore`。

## 结论

- 金额内核 `ReconRecord` **强制** `Money`（ISO-4217 三字母 + `long amountMinor`）。不能把 EA/KG 填进 currency，也不能把数量乘倍率伪装分。
- 已有独立非金额路径：`recon-entitlement` + `EntitlementReconciliationTasklet`，`measure_kind=QUANTITY`，整数券数量，不构造 Money。
- 权益数量是 `long` 件数，不是仓储十进制 + 单位 + 批次 + 序列号。**不能复用 entitlement 模型冒充 WarehouseQuantityFact。**
- `SourceAdapter` 产出仍是 `ReconRecord`，因此 WMS 数量场景不能走通用 Money job。
- 落点：新建 `recon-wms` 模块（只依赖 `recon-core`），镜像 entitlement「旁路场景」而不是改 `Money`/`ReconRecord`/`DiscrepancyClassifier`。
- S8-03 再接线 Batch/HTTP 拉 WMS 快照；本切片先固化事实解析与判差，并回归金额测试。

## 被拒替代

| 方案 | 拒绝原因 |
| --- | --- |
| 把数量写入 `ReconRecord.money` | 破坏金额红线，AC-24 明确禁止 |
| 扩展 entitlement `long quantity` | 丢失小数精度/单位/批次/序列号 |
| 直接猜写入 `recon-batch` 通用 job | 通用 job 只吃 Money 记录 |

## 实现切片

1. `recon-wms`：`WmsQuantityFact`（BigDecimal 十进制字符串 + unit + lot + serial）、JSONL 解析 S8-01 payload。
2. `WmsQuantityClassifier`：水位不齐 → SOURCE_INCOMPLETE；单位/数量/批次/序列号/缺失分别落码；CLEAN 不落单。
3. 场景码 `WMS_ONHAND_QTY`。不改 MARKETING_3WAY。
4. 测试：数量差、单位差、缺水位、金额 `DiscrepancyClassifierTest` 仍绿。
