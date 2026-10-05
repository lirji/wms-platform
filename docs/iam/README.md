# WMS 中央权限源目录

本目录属于 WMS 应用 Owner，不是运行授权或账号数据。总体接入计划由 Auth 仓库的 docs/design/wms-auth-integration/ 维护。

公开接口的唯一 scope 来源为 wms-security/src/main/resources/wms-operation-scopes.tsv。operation-bindings.tsv 显式绑定每个方法/路由的原 scope、中央能力、资源类型与实施切片；新入口未绑定时导出失败。菜单中文名称、层级与相对路径读取真实 wms-console/src/shell/nav.tsx。

```bash
python3 scripts/export-auth-catalog.py --write
python3 scripts/export-auth-catalog.py --check
python3 -m unittest discover -s scripts/tests -p 'test_auth_catalog.py'
```

生成 catalog.json（Auth 清单版本1）和 operations.json（WMS 消费绑定版本1）。目前 94 个公开操作、43 个原 scope 映射到 49 个独立能力、16 个菜单节点（4 分组/12 入口）。企业共享路径与仓级路径分开，例如 wms.masterdata.read.enterprise 与 wms.masterdata.read。后端不能把同一个旧 scope 当作两种资源通用许可。

wms_warehouse 仅使用精确 SPECIFIED_RESOURCES 仓库集合，不复用电商 store_id；wms_enterprise 仅 TENANT_ALL。目录中的 HIGH 是对写接口的保守发布分类，不向现有管理员自动授予这些能力。

用户已明确独立 local-wms → ENT-DEMO，Auth 已准备真实成员、目录和五组服务凭据。中央租户是治理 UUID，不能直接用组织名称代替。运行适配见 [接入配置](CENTRAL_RUNTIME.md)，验证结果见 [W04](W04_TEST_RESULT.md)；后续入出库、盘点调整/调拨与实际本机运行按 Auth 规范切片继续，不把目录注册当完整接入。

## W07实际运行与兼容

原wms-local已采用中央身份18090/wms-central、Auth可信HTTPS18545与独立local-wms→ENT-DEMO绑定。运行、机器期限、备份和回退范围见[W07说明](W07_RUNTIME.md)。旧8000发行方仅内部机器链使用。
