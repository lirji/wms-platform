# W07 本机中央权限运行

唯一根 `compose.yaml` 叠加 `deploy/compose.console-release.yml` 和 `deploy/compose.central-auth.yml`。WMS五个消费者独立 UID10001/0600 文件卷，SDK仅通过可信CA访问 `https://host.docker.internal:18545`。默认中央开关关闭；缺身份或凭据配置启动失败。

原 `wms-local` 端口18180–18185，原两MySQL与消息/缓存/Seata保持已有版本及业务卷。原消息开关开启时，先启动既有Kafka/Redis，等待健康，再启动应用。不得重新灌数或重建Topic。管理界面5273选择 `local-wms / wms / local`；仓资源仅指定WH-A/WH-B等明确资源，企业资源单独全企业。

完整 [Auth Runtime](https://github.com/lirji/auth-platform/blob/main/docs/design/wms-auth-integration/RUNTIME_SPEC.md) 是TLS、管理者诊断、机器续发及回退配置的权威说明。凭据/备份/精确镜像 ID 在Auth忽略目录 `.local/wms-auth-integration/`，不提交Git。对账机器JWT期限1小时，续发后通过受控初始化复制到inventory专属卷；没有自动续发。演示授权遵循原管理委派24小时上限，不是永久权限。

启动使用准备好的私密 `WMS_RUNTIME_ENV`，其保留原环境并绑定不可变镜像：

```bash
docker compose --env-file "$WMS_RUNTIME_ENV" -p wms-local \
  -f compose.yaml -f deploy/compose.console-release.yml \
  -f deploy/compose.central-auth.yml config --quiet
docker compose --env-file "$WMS_RUNTIME_ENV" -p wms-local \
  -f compose.yaml -f deploy/compose.console-release.yml \
  -f deploy/compose.central-auth.yml up -d --no-build --pull never \
  --wait --wait-timeout 240 inbound outbound inventory fulfillment serial-registry console
```

本轮原142张表行数及摘要相同，业务数据卷和数据库镜像相同。初始化挂载路径改为同字节任务源码，既有数据卷不会再次执行初始化。切换前备份、旧镜像和配置保留。真实旧序列Owner在当前隔离MySQL读取/原幂等键重放证明版本共存；没有宣称整套原目标实际全量回滚，也未恢复业务快照。

TCC只验证机器发行和内部认证链；原RM/执行开关关闭，不能声称完成TCC业务、容量或灾备。盘点/调拨已提交实物事实不能仅靠代码回退撤销。验收见 [W07_TEST_RESULT](W07_TEST_RESULT.md)。

本机共享VM出现内存回收停顿，当前五WMS进程由私密WMS_RUNTIME_ENV设置 `WMS_APP_JAVA_OPTS=-Xms64m -Xmx256m`。原较高预算保存在私密回退env；改变预算或承载数据量后需重新验证，不推导容量承诺。
