# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S0 剩余工程（XXL admin 触发 + VERSION_LOCK SBOM/CVE）本地已通过，待发布；S4-01 已在 remote main `731edd8`。
- 用户 `/goal` 要求继续 S0 剩余且不阻塞 S1 的工程，再进入后续切片。50 项 AC 与 S9 仍未完成，目标保持完整。未开始`wms-console/`。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s0-xxl-sbom（WMS）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；`/goal` 连续做到整个项目；持续 Git 发布授权；用户确认无需再确认已决定选项。
- 本轮允许：官方 XXL admin 真实触发 IT、`-Psbom` 候选 BOM/许可证/OSV 快照、VERSION_LOCK 诚实记录、快进 remote main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11 / 官方 `xuxueli/xxl-job-admin:3.4.2`。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、开始 `wms-console/`、把探针当生产 TC 或履约交付、XXL 集群/分片、为清空 OSV 而 bump Spring Boot。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S4-01 main `731edd8` 远程 verify 仍 in_progress；本轮提交后核对 |
| EG-02 TC组合/唯一TM | running | 屏障与 failure-it 已在 main；尚未做真实 TCC Try |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选 |
| EG-05 外部与非功能 | pending | S8/S9；候选 SBOM 不是生产锁 |
| Git发布 | running | 本轮待快进；无生产部署 |

## 本轮已实现（S0 XXL + VERSION_LOCK）

- `XxlAdminTriggerIT`：隔离 MySQL + 官方 admin 3.4.2，`/auth/doLogin` 后 `/jobinfo/trigger`，handler 无 TCC。
- `-Psbom` + `scripts/generate-sbom.sh`：CycloneDX / THIRD-PARTY / OSV 快照。默认 verify 不跑。
- VERSION_LOCK 记录许可证观察、Tomcat 11.0.24 三条 GHSA、本机 XXL 镜像摘要。不是生产锁定。

S4-01 `wms-fulfillment` 已在 remote main，不属于本轮 diff。

## 未完成

- 本轮快进 remote main 与远程 CI。S4-02…S9。50 项 AC。`wms-console/`。OQ-03。生产版本锁。

无生产部署。
