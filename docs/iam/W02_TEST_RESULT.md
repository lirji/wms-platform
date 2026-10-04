# W02 源目录验证

日期：2026-10-04，Gate PASS（仅源目录生成与核对，不是业务鉴权接入）。

- 5 个新目录测试通过；全部 scripts/tests 共 9 个测试通过。
- 94 个公开接口、43 个原 scope、49 个中央能力、16 个菜单节点完整覆盖；中文名称/层级/相对路由来自实际 nav.tsx。
- 新入口未绑定、重复入口、原 scope 改变、能力语义冲突、未知菜单和产物漂移均实际注入并拒绝。
- --write 可重复；--check 只读，拒绝目录漂移时原文件不变。
- Auth 的当前生产 CatalogManifest 解析器实际读取本产物并通过；内容摘要 1a83b60d1cca7873695a07388c50f7202cd4236fe76d4fa0aeb594faddc44497。
- Python语法检查、现有文档结构检查（62文档/265链接/50 AC）、verify-contracts、git diff --check 通过；OpenAPI重生成无实际差异。
- code-hygiene结果 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，仓库没有统一格式化工具；未引入格式化器或依赖。

```bash
python3 scripts/export-auth-catalog.py --check
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
python3 -m py_compile scripts/export-auth-catalog.py scripts/tests/test_auth_catalog.py
python3 scripts/check-docs.py
./scripts/verify-contracts.sh
```

Auth/WMS实际运行、服务身份、主体映射、SQL范围和浏览器交互尚未验证；此片没有修改前端、后端作业、安全过滤器或运行配置。CI增加中央目录漂移检查；全仓Java流水线结果独立记录，不能用本片Python工具测试替代。
