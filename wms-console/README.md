# wms-console

管理端与 PDA 适配页。数据只来自后端 API，禁止页面写死仓库、SKU 或库存。

## 命令

```bash
cd wms-console
npm ci
npm run typecheck
npm test
npm run build
npm run dev
```

## 本地联调

1. `./scripts/seed-local.sh --profile isolated-wms` 向显式 JDBC 写入种子。
2. 从 auth-platform 执行 `WMS_IAM_CREDENTIALS="$PWD/../wms-platform/deploy/.env.casdoor.json" python3 deploy/wms-platform-provision.py`，再把 `VITE_OIDC_ISSUER` / `VITE_OIDC_CLIENT_ID` 写入本目录 `.env`（示例见 `.env.example`）。
3. 启动 inbound `:18181`、outbound `:18182`、inventory `:18183`、fulfillment `:18185`。
4. `npm run dev` 通过 Vite 代理访问上述服务，不直连数据库。默认监听 `WMS_UI_PORT`（4181），与 auth-platform 能力门户 `catalog.json` 一致；`/healthz` 供门户跨域探测，正式入口是 `/login`。

登录后仓上下文在 URL：`/w/:warehouseId/...`。PDA 收货为 `/pda/:warehouseId/receive`。旧路径会重定向。架构见 `docs/design/console-frontend/FRONTEND_ARCHITECTURE.md`。

数量始终按字符串输入和显示，不在浏览器做发运量浮点运算。202 只表示已受理；库存同步看 `stockSyncStatus`。
