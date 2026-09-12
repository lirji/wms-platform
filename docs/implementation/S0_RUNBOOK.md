# 本地运行与验证

文件名保留以兼容已有链接；内容已按 `main 3e2c720`（2026-09-13）更新，覆盖当前实现。快速导航：[容器运行](../../deploy/README.md)、[连接清单](../operations/INFRASTRUCTURE.md)、[版本记录](VERSION_LOCK.md)、[交付状态](../delivery/wms-v1/DELIVERY_STATUS.md)。

## 运行路径

| 方式 | 前置与用途 | 边界 |
| --- | --- | --- |
| 全部容器 | Docker/Compose；按部署说明配置 `.env` 后运行 `./deploy/up.sh` | 容器内编译；五后端 + console；inventory 默认仅 Cell A |
| 本机后端开发 | JDK 21、Maven Wrapper、Docker；隔离中间件 + 本机 JAR | 为目标服务设置 JDBC/用户/口令及 OIDC；不要同时占用容器应用端口 |
| 本机前端开发 | Node 22、npm lock、可达后端与 OIDC | Vite 默认 `127.0.0.1:4181`，Docker 控制台为 18180 |

所有测试和故障注入使用本项目隔离容器，不能连接共享 dev-infra 或生产库。初次解析 Maven/npm/镜像需要网络。详细版本来自仓库声明，本次没有重新核验现场安装版本。

## 配置与启动

已有 `.env` 时直接核对，不复制覆盖。首次从 `.env.example` 建立本机配置并替换占位口令，还须设置 OIDC issuer/client ID、应用可达 JWKS 和序列号受信主体。默认模板不能直接通过完整 `up.sh` 就绪检查。仅起中间件可执行：

```bash
docker compose -p wms-local -f deploy/compose.local.yml --env-file .env config --quiet
docker compose -p wms-local -f deploy/compose.local.yml --env-file .env up -d
```

初始化脚本仅在空数据卷运行，业务迁移在应用 JDBC 装配时执行。本机进程使用宿主端口（MySQL 18306/18307/18308、Kafka 18992 等），容器使用服务 DNS，完整映射见连接清单。Seata 本机和容器回调地址必须匹配客户端位置。

本机只编译打包可执行 `./mvnw -B -ntp -DskipTests package`，它不算测试通过。配置目标服务环境后，例如启动 inventory：

```bash
java -jar wms-inventory/target/wms-inventory-0.1.0-SNAPSHOT.jar
```

默认后端端口依次为 inbound 18181、outbound 18182、inventory 18183、serial-registry 18184、fulfillment 18185，本机单服务可用 `WMS_HTTP_PORT` 覆盖。无 JDBC/鉴权配置时仅能检查进程存活，不能接业务。issuer 为空时业务路径拒绝；配置后仍需有效令牌和 scope/企业/仓权限。

前端在 `wms-console/` 执行 `npm ci`、`npm run dev`；OIDC 环境和代理说明见[前端 README](../../wms-console/README.md)。未配置身份服务时控制台停在登录/配置态。已有本地 Casdoor 开通记录不代表本次已登录或生产 IdP 已选定。

## 按改动选择验证

| 改动 | 首选检查 | 说明 |
| --- | --- | --- |
| 纯文档 | `python3 scripts/check-docs.py`、`git diff --check` | 链接、围栏、50 AC/任务编号及 SQL 注释；不是业务验收 |
| Compose 文档/配置 | `docker compose --env-file .env.example config --quiet` | 只验证仓库模板可解析；不打印真实配置，不启动栈 |
| API/权限/契约 | `./scripts/verify-contracts.sh` + 受影响 HTTP IT | 生成物一致与兼容检查；不能代替真实业务测试 |
| 局部后端 | 受影响模块及依赖的测试 | 按失败场景选择，不因一项小改动重复跑全部探针 |
| 前端 | `npm run typecheck`、`npm test`、`npm run build` | 在 `wms-console/` 执行；先 `npm ci` |
| 跨服务组合/发布回归 | 下方完整命令 | Docker 不可用、缺必需测试、跳过或失败都不能算通过 |

完整后端回归与现有 CI 保持一致；默认全仓验证只执行一次，后续 profile 限定独立 test-support 模块：

```bash
./mvnw -B -ntp verify
python3 scripts/check-required-its.py --suite default
python3 scripts/smoke-services.py
./mvnw -B -ntp -pl wms-test-support -Pwarehouse-it verify
./mvnw -B -ntp -pl wms-test-support -Ptc-it verify
./mvnw -B -ntp -pl wms-test-support -Pfailure-it verify
python3 scripts/check-required-its.py --suite failure
```

第一条构建并执行测试；第二条核对默认必需 IT 名单；第三条才启动实际 JAR 做进程 smoke。`smoke-services.py` 当前覆盖 inbound/outbound/inventory/fulfillment，登记服务的真实进程验证在相应 IT 中；不能声称 smoke 覆盖五服务全部业务。

`warehouse-it` 包含真实 MySQL、分片/Fence、Kafka 和官方 XXL admin 触发等探针；`tc-it` 覆盖真实 TC 查询限制、持久终态审计和恢复屏障；`failure-it` 对测试登记的资源注入故障。三组报告都写入 `wms-test-support/target/failsafe-reports`，需要留存时须在下一组运行前复制，CI 会分别归档。真实 TM/RM 执行链路另见[运行 RM](RUNTIME_TCC_RM.md)和[履约执行](FULFILLMENT_EXECUTION.md)，不再以早期探针的范围概括所有当前实现。

## 数据、安全与专项验收

- `./scripts/seed-local.sh --profile isolated-wms`：显式提供 Cell A/B 库存库及 inbound/outbound/fulfillment 的 JDBC、用户和口令；拒绝共享 dev-infra 地址。演示履约 attempt 只写 `PLANNED`，不制造 TCC `ALLOCATED`。
- `./scripts/generate-sbom.sh`：单独生成 Maven 聚合 BOM、许可证与 OSV 快照，需要网络；不加入默认 verify。仅在依赖相关变化时刷新，历史命中不能声称已修复或最新无漏洞。
- `./scripts/run-capacity.sh --scenario agreed-peak`：无签署容量输入时拒绝，不把合成峰值当目标。
- `./scripts/run-restore-drill.sh`：没有外部库时仅执行隔离恢复测试；生产恢复承诺需要获授权目标和实测证据。

真实设备与模拟器、单组件成功与业务恢复、代码回退与数据补偿分别记录。任务日志可保留在忽略的 `.local/` 或 `target/`，关键结论须进入交付证据；不要输出完整环境或带凭据进程参数。

## CI 与发布边界

[verify.yml](../../.github/workflows/verify.yml) 执行脚本/文档/契约检查、Java 默认验证、进程 smoke、三组独立 profile 和前端检查，并上传测试报告；没有生产部署步骤。该基线的 [main CI 已通过](https://github.com/lirji/wms-platform/actions/runs/34721632607)。文档整理复用代码基线证据，不冒充重跑。

现有规则允许纯文档提交使用 `[skip ci]` / `[ci skip]` / `[no ci]`，不适用于代码修改；使用时仍先本地完成文档相关检查，并在交付摘要明确 CI 跳过。未完成远程 CI 不能记为成功。持续 Git 授权只涵盖正常提交/推送，不等同于生产部署授权。
