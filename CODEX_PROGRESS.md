# Codex Progress

## 任务目标

按已批准计划做到 S9 与 50 项 AC。未发明 OQ-03。

## 已完成

- WMS S0–S7 及 S8-01/S8-04/S9-02/S9-03/S9-04/S9-06 在更早的 `origin/main`。
- route-gate 修复与 AC-24 HTTP IT 在 `origin/main` `4dee112`。
- recon S8-02/S8-03 在 recon `origin/main`；`WmsExportContractTest` 复跑 1/0。
- 公共工作流 Stage 5：`compose.yaml` 容器编译启动五服务与 console，已在远程 main `87a233b`。
- 控制台登录页、`returnTo` 消毒、OIDC client 默认 `wms-platform`、Vite `:4181`/`/healthz`，以及设计文档 `docs/design/11-edge-resilience.md`（2026-09-12，`feat/console-login-oidc`）。未配 OIDC 停在登录配置态。不是 UI accepted。

## 未完成

- 将 `feat/console-login-oidc` 合入远程 main（等当前 main verify 结束再推，避免 `cancel-in-progress`）。
- S8-05 真实设备。S9-01 签署容量。OQ-03。UI accepted。50 AC 全量通过。

## 下一步

main verify `34678521646` 结束后快进发布控制台登录切片；S8-05 / S9-01 / AC-42 / UI 保持 blocked。不把 compose 健康或登录页写成全通过。
