# 本地容器运行

根 [compose.yaml](../compose.yaml) 在容器内编译并启动五个后端应用与控制台，并包含本项目隔离中间件。inventory 默认接 Cell A；Cell B 只有数据库，没有第二个库存应用。连接、版本与凭据变量见[连接清单](../docs/operations/INFRASTRUCTURE.md)和[版本记录](../docs/implementation/VERSION_LOCK.md)。

## 启动前配置

1. 准备 Docker、支持 `include` 的 Compose 与镜像/依赖下载网络。
2. 若尚无 `.env`，从 `.env.example` 复制；保留已有配置，不覆盖。替换占位口令，真实值不提交 Git。
3. 设置 `WMS_OIDC_ISSUER`、`WMS_OIDC_CLIENT_ID` 和应用可达的 `WMS_OIDC_JWK_SET_URI`。issuer 要与令牌一致；浏览器和容器访问身份服务的地址可能不同。
4. 设置 `WMS_SERIAL_ALLOWED_SUBJECTS` 为明确的受信服务主体名单；登记服务在 JDBC 模式下拒绝空名单。仅填主体不等于具备业务 scope/企业/仓权限。
5. 核对宿主端口：应用 18180–18185、MySQL 18306–18308、Kafka 18992、Redis 18379、Seata 18091/17091、XXL 18080，默认只绑定 `127.0.0.1`。

`.env.example` 原样不是完整可用配置：缺 OIDC 时业务访问拒绝且 readiness 不通过，登记主体为空还会导致启动失败。控制台 OIDC 值在镜像构建时注入，修改后需重建 console。

## 命令

从仓库根目录运行：

```bash
# 只做配置语法检查，不启动容器；不打印展开后的机密
docker compose --env-file .env config --quiet
./deploy/up.sh
# 停止本项目，默认保留数据卷
./deploy/down.sh
```

`up.sh` 会构建镜像并等待五个后端 `/actuator/health/readiness` 和控制台入口。等待失败不自动删除容器或数据；先用 `docker compose --env-file .env ps` 核对失败服务，再查相应日志并避免复制凭据。`/actuator/health/liveness` 只表示存活，readiness 表示配置与依赖检查通过，均不等于业务验收。

**自定义端口的现有限制**：Compose 会读取 `.env`，但 `up.sh` 的等待 URL 从当前 shell 读取 `WMS_*_HOST_PORT`，没有加载 `.env` 中的端口值。改变应用宿主端口时，调用前还要把同名端口变量 export 到当前 shell；否则应用可能已正常启动，脚本却等待默认端口。无需把整份含密码的 `.env` 输出或导入 shell。本次仅记录这一脚本差异。

`deploy/down.sh --volumes` 会删除本编排数据卷；它是有意清除隔离数据时的危险选项，不属于日常停止或迁移步骤。不要停止或清空共享 dev-infra，也不要用删卷解决已有数据库迁移问题。

只启动中间件、本机运行 JAR 的步骤见[本地运行手册](../docs/implementation/S0_RUNBOOK.md)。

## 功能开关与初始化

- 默认关闭各服务运行消息、序列号远程客户端、TC 审计、原生 RM 和自动履约执行。需要按[连接清单](../docs/operations/INFRASTRUCTURE.md)关联的专题文档准备 Topic/Cell 路由、网络边界和服务令牌，不能仅将开关全部改为 true。
- 应用容器使用 Compose DNS 和内部端口，宿主数据库端口仅供本机客户端连接。基础镜像可用 `WMS_JDK_IMAGE`、`WMS_JRE_IMAGE`、`WMS_NODE_IMAGE`、`WMS_NGINX_IMAGE` 覆盖；替换后需重验。
- `deploy/init/` 只在空 MySQL 数据卷首次执行。旧卷缺账号/schema 时需要显式迁移或初始化方案；业务表由 Flyway 维护。
- 演示数据通过 `scripts/seed-local.sh --profile isolated-wms` 写入隔离数据库，需要明确提供 Cell A/B 和应用库凭据；不在页面写死业务数据。

控制台入口为 `http://127.0.0.1:18180/`。本次文档核对未启动或登录环境；生产部署、真实设备、容量、备份恢复与全部 50 项 AC 均需独立证据。
