# wms-console

管理端与 PDA 适配页。React 19 + Ant Design 6。数据只来自后端 API，禁止页面写死仓库、SKU 或库存。列表页建单和单据命令在抽屉里，不占整页。

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
4. `npm run dev` 通过 Vite 代理访问上述服务，不直连数据库。默认监听本机 `4181`，只给本地开发；`/healthz` 同样可用，正式入口是 `/login`。
5. 能力门户走 Docker 控制台 `127.0.0.1:18180`（auth-platform `WMS_UI_PORT`）：根 `.env` 写 `WMS_OIDC_ISSUER=http://localhost:8000`、`WMS_OIDC_CLIENT_ID=wms-platform`，容器拉 JWKS 用 `WMS_OIDC_JWK_SET_URI=http://host.docker.internal:8000/.well-known/jwks`，再 `docker compose --env-file .env up -d --build --no-deps console`。provision 默认登记 `18180` 回调，并保留本机 Vite `4181`。任务页读已实现的 `GET /api/wms/v1/jobs?warehouseId=`，不打尚未落地的 `GET /tasks`。

登录后仓上下文在 URL：`/w/:warehouseId/...`。PDA 收货为 `/pda/:warehouseId/receive`。旧路径会重定向。架构见 `docs/design/console-frontend/FRONTEND_ARCHITECTURE.md`。provision 会给 `wms-ops` / `wms-wh-a` / `wms-wh-b` 写入 OpenAPI 作业 scope；已登录会话必须重新登录才能拿到新 JWT。顶栏展示仓范围与权限名，不展示 access_token。401 是会话过期，不是权限不足。

数量始终按字符串输入和显示，不在浏览器做发运量浮点运算。202 只表示已受理；库存同步看 `stockSyncStatus`。
