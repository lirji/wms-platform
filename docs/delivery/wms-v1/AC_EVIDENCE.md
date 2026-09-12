# WMS v1 AC 证据（S9-05 进行中）

权威范围仍是 `DELIVERY_PLAN.md` 的 50 项 AC。本表只记当前仓库能核验的证据，不把本地 IT、探针或 simulator 升格为生产通过。

判定：

- `local-pass`：有真实测试命令与结果，但缺跨服务/设备/签署容量等必选环境。
- `blocked`：缺授权环境或未决业务输入，不能宣称通过。
- `open`：实现或验收仍缺。

| AC | 当前 | 证据 | 缺口 |
| --- | --- | --- | --- |
| AC-01 | local-pass | `MasterdataHttpIT` 越仓 403 | 未做 console 权限黑盒 |
| AC-02 | local-pass | 种子 SKU/单位/临期 Instant；`skuLotUnitsIncludeCasePackTwelveToOne` | OQ-03 未确认 |
| AC-03 | local-pass | `InventoryConcurrencyIT` 20 并发 10 胜 | 非 HTTP 黑盒 |
| AC-04 | local-pass | 入账幂等/异内容冲突 IT | 响应丢失正式黑盒 |
| AC-05 | local-pass | 失败注入回滚 IT | 来源 PENDING 恢复黑盒 |
| AC-06 | local-pass | `ShardingRouteIT` | 三服务独立发布验证 |
| AC-07 | local-pass | `InboundReceiptIT` 收货质检上架 | 端到端 HTTP+库存 |
| AC-08 | local-pass | 序列号登记 IT | 两仓并发正式链 |
| AC-09 | local-pass | 观察恢复 IT | 乱序确认现场 |
| AC-10 | local-pass | TCC/XXL handler 本地 | 真实 TC 超时 + XXL 集群 |
| AC-11 | local-pass | `SeataTccRecoveryIT` 范围 | TM 重启全链路 |
| AC-12 | local-pass | 履约屏障本库 | 真实 TC 终态查询 |
| AC-13 | local-pass | 出库部分拣发 IT | HTTP/WCS/设备 |
| AC-14 | local-pass | 取消回库协议 IT | 生产逆向链 |
| AC-15 | local-pass | 效期重校验 IT | STARTED 授权现场 |
| AC-16 | local-pass | `TransferConservationIT` 两库 | 跨库存生产 HTTP |
| AC-17 | local-pass | `SerialTransferRecoveryIT` | 旧授权现场 |
| AC-18 | local-pass | `CountFreezeRaceIT` | 跨服务排空 |
| AC-19 | local-pass | 盘点调整/序列号 IT | 预占冲突生产链 |
| AC-20 | local-pass | `JobRunIT`/`JobInterruptRecoveryIT`/`JobLeasePreemptIT` | XXL 换主现场 |
| AC-21 | local-pass | `OutboxRecoveryLoadIT` | 消费者一次效果跨进程 |
| AC-22 | local-pass | `InventoryProjectionIT` | 重建追平现场 |
| AC-23 | local-pass | `StockInternalReconcileIT` | 工作台审批现场 |
| AC-24 | local-pass | WMS `SnapshotExportIT`/`SnapshotHttpIT`；recon `WmsExportContractTest` 消费 WMS JSONL | 仍缺跨仓库进程联调与金额回归现场 |
| AC-25 | blocked | 出库 simulator / UNKNOWN sweep（仅模拟，单独列） | 无授权真实设备/协议环境（S8-05） |
| AC-26 | open | 2026-09-12 Casdoor 活走查：收货/质检/上架/准备跨仓/拣/部分发/未拣回库；202 显示待同步；403/409 横幅；见 [AC26_LIVE_WALK.md](AC26_LIVE_WALK.md) | 跨仓 ALLOCATED 需真实 TC；收货同键非恢复；未做断网/对账修复/设备 |
| AC-27 | blocked | `run-capacity.sh --scenario agreed-peak` 无签署输入则退出 2 | 缺 OQ-05 签署峰值；correctness 只对应既有并发 IT |
| AC-28 | local-pass | `WarehouseMigrationIT` 两 MySQL 全量/增量/切 epoch/旧写拒绝 | 无生产停写窗口（OQ-06） |
| AC-29 | local-pass | `CompatibilityMatrixIT`/`CompatibilityGateTest`/`verify-contracts.sh` | 无生产滚动升级/开关演练现场 |
| AC-30 | local-pass | `IsolatedRestoreIT` 第二库恢复并打印 localRtoMs/localRpoMs | 不是签署生产 RTO/RPO |
| AC-31 | local-pass | `SeedReplayIT` / 入出库履约 ReplayIT + `seed-local.sh` | console 演示数据网络检查 |
| AC-32 | local-pass | CI `warehouse-it`/`failure-it` 无文件则失败；`check-required-its.py` 禁止 skip | 远程 runner 最终证明 |
| AC-33 | local-pass | 三库账号/独立进程 smoke | 独立发布负例 |
| AC-34 | local-pass | T1/T2/T3 协议 IT | 跨进程崩溃现场 |
| AC-35 | local-pass | 取消墓碑/乱序 IT | 并发取消生产链 |
| AC-36 | local-pass | claim 并发 IT | 跨服务授权 |
| AC-37 | local-pass | 冻结排空本库 | 三服务在途 permit |
| AC-38 | local-pass | 质量撤销 IT | 与授权并发现场 |
| AC-39 | local-pass | 内部对账 watermark | 跨系统水位联调 |
| AC-40 | open | Casdoor 收货/拣/发 202 横幅「货已执行，库存待同步」 | 库存仍 PENDING，无 UNKNOWN 设备恢复端到端 |
| AC-41 | local-pass | Fence/空回滚 IT | 正式 TM 进程 |
| AC-42 | blocked | TC 探针 | TM/TC 宕机保留现场 |
| AC-43 | local-pass | Fence 同连接 IT | 双分片 RM 重启现场 |
| AC-44 | local-pass | `ContextIsolationIT` | 非正式 Outbox/HTTP Try |
| AC-45 | local-pass | owner/XID CAS IT | 实际 RPC 换 branch |
| AC-46 | local-pass | 启动绑定 IT | 独立 TM 崩溃 |
| AC-47 | local-pass | 分批/换键 IT | HTTP-MQ 离线重报 |
| AC-48 | local-pass | 安全关闭 IT | 旧回执并发现场 |
| AC-49 | local-pass | UNKNOWN/STARTED 拒绝重做 | 设备核验入口 |
| AC-50 | local-pass | 补偿一次入账 IT；`FailureDigestReplayIT` 旧摘要重放 | 版本共存生产现场 |

未决：OQ-03 单位/效期默认不得编造。S8-05 无授权设备保持 blocked，模拟不得当作硬件通过。S9-01 缺签署容量输入。S9-02/S9-04 仅本地两库演练，不是生产停写窗口或签署 RTO/RPO。S9-03 本地矩阵已有；S9-06 已把 AC-45..50 列入 CI 必选名单。

总体：**不能**把本表当作 50 AC 已通过。
