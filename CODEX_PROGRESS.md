# Codex Progress

## 任务目标

按已批准计划把整个 WMS v1 做到 S9 与 50 项 AC。切片完成后自动下一片，不要等「继续」。当前切片 S5-02。未发明 OQ-03。未到计划 S8 前不创建 `wms-console/`。

## 已完成

- S0、S4-01…S5-01 已在 remote main `017171c`。

## 已修改文件（本轮）

- 新建 `wms-integration` 与 `wcs` 端口/simulator
- `ContextIsolationIT` 增加 integration 无 Seata
- `docs/design/01-architecture.md` 模块组织行

## 未完成

- 发布 S5-02（先等 S5-01 main CI）。S5-03 STARTED/派发身份。S5-04…S9。50 项 AC。OQ-03。S8 才做 `wms-console/`。

## 下一步建议

1. 本地 verify 后等 S5-01 CI，快进 main。
2. 立刻做 S5-03。不要把目标缩成只做 S5-02。

## 恢复 Prompt

读取 CODEX_PROGRESS.md。从第一个未完成切片继续，不要要求反复输入继续。不要发明 OQ-03。未到 S8 不要创建 wms-console。
