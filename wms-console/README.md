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
2. 配置 `VITE_OIDC_ISSUER` / `VITE_OIDC_CLIENT_ID`（Casdoor 见 auth-platform `deploy/wms-platform-provision.py`）。
3. 启动 inbound `:18181`、outbound `:18182`、inventory `:18183`、fulfillment `:18185`。
4. `npm run dev` 通过 Vite 代理访问上述服务，不直连数据库。

数量始终按字符串输入和显示，不在浏览器做发运量浮点运算。202 只表示已受理；库存同步看 `stockSyncStatus`。
