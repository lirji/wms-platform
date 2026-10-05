# 本地执行 Driver

将开发任务统一转成对本机 Codex CLI、Cursor Agent CLI 的调用。模块负责读取 manifest、注册与健康检查、按能力选择 Driver、校验执行策略、管理子进程超时，并返回退出码、输出和执行日志。仓储业务应用不依赖本模块，Compose 不启动 Driver 服务。

`codex-local` 调用 `codex exec`；`cursor-local` 探测 `agent` 或 `cursor-agent`。能力与超时配置位于 `src/main/resources/execution-driver-manifest/v1/`。配置中的 `active` 是期望状态，实际可用性由健康检查决定；版本检查通过也不代表账号额度或远程执行可用。

## 使用

从项目根目录运行；需要 Java 21、Maven，以及对应工具的安装和登录状态。

```bash
./scripts/driver-check.sh list
./scripts/driver-check.sh health codex-local
./scripts/driver-check.sh health cursor-local
./scripts/driver-check.sh capabilities cursor-local
./scripts/driver-check.sh check
```

`smoke <id>` 会实际调用 AI 工具读取仓库；`execute-tests <id>` 会实际调用工具运行 `DriverAuditLoggerTest`。这些命令依赖账号、网络和额度，可能产生调用费用。默认 Driver 为 `cursor-local`。

## 验证范围

```bash
./mvnw -B -ntp -pl wms-execution-driver verify
python3 scripts/format-java.py --check --module wms-execution-driver
bash -n scripts/driver-check.sh
```

自动测试包含 30 个单元测试和 3 个使用 CLI 夹具的真实子进程集成测试，验证选择、参数适配、策略拒绝及超时等行为；不调用真实 AI 账号。真实 Codex/Cursor 执行需要另外运行上述 smoke 命令，不能由自动测试通过推断其结果。
