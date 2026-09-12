# Codex Progress

## 任务目标

按已批准计划做到 S9 与 50 项 AC。未发明 OQ-03。

## 已完成

- WMS S0–S7 及 S8-01/S8-04/S9-02/S9-03/S9-04/S9-06 在更早的 `origin/main`。
- route-gate 修复与 AC-24 HTTP IT 在 `origin/main` `4dee112`。
- recon S8-02/S8-03 在 recon `origin/main`；`WmsExportContractTest` 复跑 1/0。
- 公共工作流 Stage 5：`compose.yaml` + `deploy/app.Dockerfile` 在容器内编译并启动 inbound/outbound/inventory/serial-registry/fulfillment 与 console（2026-09-12，工作树 `.local/main-integration`，项目 `wms-local`）。五服务 `/actuator/health` 与控制台 `http://127.0.0.1:18180/` 为 200；未配 OIDC 时业务路径 403。未操作共享 dev-infra。健康 UP 不是 50 AC。

## 未完成

- 将本 Stage 5 提交合入远程 main，并核对 60m java timeout 的 main verify。
- S8-05 真实设备。S9-01 签署容量。OQ-03。UI accepted。50 AC 全量通过。

## 下一步

发布 `feat/docker-app-runtime` 后继续 S9-05 证据；S8-05 / S9-01 / AC-42 / UI 保持 blocked，除非给出设备、签署容量、OIDC 联调或 TM/TC 宕机环境。不把 compose 健康写成全通过。
