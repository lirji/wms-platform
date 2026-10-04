# WMS 中央权限源目录

本目录属于 WMS 应用 Owner，不是运行授权或账号数据。总体接入计划由 Auth 仓库的 docs/design/wms-auth-integration/ 维护。

公开接口的唯一 scope 来源为 wms-security/src/main/resources/wms-operation-scopes.tsv。operation-bindings.tsv 显式绑定每个方法/路由的原 scope、中央能力、资源类型与实施切片；新入口未绑定时导出失败。菜单中文名称、层级与相对路径读取真实 wms-console/src/shell/nav.tsx。

```bash
python3 scripts/export-auth-catalog.py --write
python3 scripts/export-auth-catalog.py --check
python3 -m unittest discover -s scripts/tests -p 'test_auth_catalog.py'
```

生成 catalog.json（Auth 清单版本1）和 operations.json（WMS 消费绑定版本1）。目前 94 个公开操作、43 个原 scope 映射到 49 个独立能力、16 个菜单节点（4 分组/12 入口）。企业共享路径与仓级路径分开，例如 wms.masterdata.read.enterprise 与 wms.masterdata.read。后端不能把同一个旧 scope 当作两种资源通用许可。

wms_warehouse 仅使用精确 SPECIFIED_RESOURCES 仓库集合，不复用电商 store_id；wms_enterprise 仅 TENANT_ALL。仓级调拨的列表/详情 Owner 过滤与各作业接入仍需后续实施。目录中的 HIGH 是对写接口的保守发布分类，不向现有管理员自动授予这些能力。

此目录可供 Auth 的 CatalogManifest 校验和后续 Owner 发布，但没有执行应用注册、成员导入、授权、配置切换或远端发布。组织映射未确认：ENT-DEMO 归独立 local-wms 还是 local-commerce。不得直接把旧 Casdoor 组织名替代中央租户 UUID，也不得把来源目录当作已完成业务鉴权。
