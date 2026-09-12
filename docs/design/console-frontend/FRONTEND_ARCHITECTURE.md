# 控制台前端架构

依据 [BRIEF.md](BRIEF.md) 与已批准交接/契约。仓库只提供约束，不提供信息架构抄本。

## 1. 目标与非目标

目标：一个可部署的作业台，按仓作业、按契约读数、按命令写，覆盖交接中的用户路径与页面状态。

非目标：微前端、独立 PDA 工程、页面 Mock 库存、客户端浮点决定发运量、把健康检查当业务验收。

## 2. 角色与路由树

```text
/login                         登录
/callback                      OIDC 回调
/                              已登录 → 跳到 /w/:warehouseId
/w/:warehouseId                工作台首页（待办入口，无写死数字）
/w/:warehouseId/catalog        商品 / 库位（只读）
/w/:warehouseId/inbound        入库工作台
/w/:warehouseId/stock          库存台账
/w/:warehouseId/fulfillment    履约与出库
/w/:warehouseId/transfers      调拨
/w/:warehouseId/counts         盘点
/w/:warehouseId/jobs           任务与设备
/w/:warehouseId/recon          对账差异
/pda/:warehouseId/receive      PDA 收货（独立壳）
```

范围外：OMS/ERP 门户、设备固件 UI、对账导出桌面工具。

旧路径 `/inbound` 等重定向到带仓的新路径，避免书签断裂。

## 3. 形态

**选择：单应用。**

| 方案 | 结论 |
| --- | --- |
| 单应用 | 采用。一个构建、一套 OIDC、一套反代 |
| workspace 多包 | 拒绝。当前只有一个可部署 UI |
| 微前端 | 拒绝。没有多团队独立发布门禁 |

## 4. 前端栈

仓库已锁定 React 19 + TypeScript + Vite 7 + `oidc-client-ts` + Vitest。这是约束，不是选型发现。

| 决策 | 选项 | 选择 | 拒绝原因 |
| --- | --- | --- | --- |
| 框架 | React / Vue | React | 现有锁文件、测试、Docker、OIDC 适配 |
| 语言 | TypeScript / 无类型 | TypeScript | OpenAPI 与数量字符串契约 |
| 样式 | tokens+CSS / utility / CSS-in-JS | tokens + 一份 CSS | 不引入第二套样式运行时 |
| 服务端数据 | fetch 包装 / 查询库 | fetch 包装 | 列表短、202 轮询有界，不需要第二缓存 |
| 路由 | react-router | 框架默认 | 已用于登录与回调 |
| 表格/表单 | 轻量 / 管理套件 | 轻量 | 列来自契约字段，不绑 admin kit 的假数据 |

## 5. 模块与目录（提议）

```text
wms-console/src/
  app/           路由与会话装配
  auth/          OIDC 与 returnTo
  api/           前缀路由、信封解析、幂等键
  design/        色板、间距、字号 tokens
  shell/         桌面壳、PDA 壳、仓选择（写 URL）
  shared/ui      状态条、表、数量文本
  features/*     按作业：home catalog inbound stock fulfillment transfer count jobs recon pda
  pages/         仅登录/配置等无仓页
```

壳与作业分离。作业页不得 import 另一作业的领域组件。路由级按页拆分。

## 6. 状态与数据流

| 种类 | 放哪里 |
| --- | --- |
| 当前仓 | URL `:warehouseId` |
| 会话 | oidc-client-ts user |
| 列表 / asOf | 每次进入页面向活 API 拉取，不进全局 store |
| 幂等键 | sessionStorage，按「一次有意操作」复用 |
| 扫码框焦点 | 组件局部 |

客户端：`GET/POST /api/wms/v1/...` → `routeFor` 到四服务前缀。解析 `CursorPage.items` 与错误体。数量字段只当字符串渲染。

演示行必须来自 seed 后的 API，测试 fixture 不得冒充演示数据。

## 7. 屏幕状态矩阵

每个作业页至少：

| 状态 | 行为 |
| --- | --- |
| loading | 保留过滤条件，按钮禁用 |
| empty | 写明当前仓与过滤，给出允许动作 |
| error / 5xx | 服务不可用，不伪装无权限 |
| forbidden / 401/403 | 说明拒绝；动隐藏但不替代服务端权威 |
| 202 accepted | 处理中 + operationId，禁止显示成功 |
| 409 | 最新记录 + 需重确认；不换幂等键 |
| stale | asOf / lagSeconds 提示刷新 |
| TCC | 「库存已预留，等待全局完成」，无强制释放 |
| PDA | 文字+tone，不能只靠颜色 |

## 8. 视觉 tokens（假设品牌）

工业仓储，8px 间距。

| token | 值 |
| --- | --- |
| `--ink` | `#102027` |
| `--slate` | `#173f4a` |
| `--paper` | `#f4f1ea` |
| `--card` | `#fffdf8` |
| `--line` | `#d5cfc3` |
| `--accent` | `#c4a35a` |
| `--ok` / `--warn` / `--err` | `#1b5e20` / `#8a5a12` / `#8b1e1e` |
| 半径 | 8px 卡片，999px 导航片 |
| 字号 | 12 / 14 / 18 / 28 |

## 9. 视口策略

交接已要求同应用 PDA 页，因此不单做桌面、也不另开移动产品。

| 表面 | 宽度 | 行为 |
| --- | --- | --- |
| 桌面工作台 | ≥1280 | 顶栏 + 横滑模块导航 + 密表 |
| 窄桌面 | 768–1279 | 导航横滑，表横向滚动 |
| PDA 路由 | ≥390 | 独立壳、大触控、扫码框 autofocus |

## 10. API / 认证 / 错误假设

- 认证：OIDC 授权码 + PKCE；空 issuer 不回退免认证
- 授权：令牌仓范围；UI 隐藏不可执行动作
- 错误体：`code` / `message` / `retryable`
- 写操作：`Idempotency-Key`；扫描 `scanSequence` 由后续切片接契约
- 时区：展示可按仓，请求 UTC
- TP99：本切片不新增后端接口；已有读接口预算仍以服务端文档为准，前端不宣称达标

任务页绑定已落地的 `GET /api/wms/v1/jobs?warehouseId=`（inventory）。OpenAPI `GET /warehouses/{id}/tasks` 尚未实现，页面不伪造任务表。

## 11. 未决

- 设备 UNKNOWN 与真实硬件仍 blocked（S8-05）
- 主数据写 API 未实现，catalog 保持只读
- AC-26 全链路写作业（收货→上架→跨仓→拣发）仍未做，不是 50 AC accepted
