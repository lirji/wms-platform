#!/usr/bin/env python3
"""校验本仓文档与迁移注释；结构通过不代表业务验收通过。"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    errors = []
    docs = [p for p in ROOT.rglob("*.md") if not any(x in p.parts for x in ("target", ".local", ".git", ".idea"))]
    links = 0
    for path in docs:
        text = path.read_text()
        if sum(line.startswith("```") for line in text.splitlines()) % 2:
            errors.append(f"{path}: 未闭合代码围栏")
        for line_number, line in enumerate(text.splitlines(), 1):
            if line.rstrip() != line:
                errors.append(f"{path}:{line_number}: 尾随空格")
        for link in re.findall(r"\]\(([^)]+)\)", text):
            if re.match(r"\w+://", link) or link.startswith("#"):
                continue
            target = (path.parent / link.split("#")[0].strip("<>")).resolve()
            # 设计证据引用同工作区其他项目，远程CI无这些仓库，不伪造其已核验。
            if not target.is_relative_to(ROOT):
                continue
            links += 1
            if not target.exists():
                errors.append(f"{path}: 目标不存在 {link}")
        for language, body in re.findall(r"```([^\n]*)\n(.*?)```", text, re.S):
            if language == "json":
                try:
                    json.loads(body)
                except ValueError as error:
                    errors.append(f"{path}: JSON {error}")
    plan = (ROOT / "docs/delivery/wms-v1/DELIVERY_PLAN.md").read_text()
    ids = re.findall(r"^\| AC-(\d+) \|", plan, re.M)
    if ids != [f"{number:02}" for number in range(1, 51)]:
        errors.append("AC必须为01..50且无重复")
    tasks = re.findall(r"^- (S\d+-\d+[a-z]?) ", plan, re.M)
    if len(tasks) != len(set(tasks)):
        errors.append("实施任务编号重复")
    for path in ROOT.glob("wms-*/src/**/db/**/*.sql"):
        text = path.read_text()
        for body in re.findall(r"CREATE TABLE .*?;", text, re.S | re.I):
            if not re.search(r"\)\s*(?:ENGINE=.*?)?COMMENT\s*=", body, re.I):
                errors.append(f"{path}: 缺表注释")
            for line in body.splitlines():
                if re.match(r"\s+\w+\s+(?:VARCHAR|CHAR|BIGINT|INT|TINYINT|DECIMAL|DATETIME)\b", line, re.I) and "COMMENT" not in line.upper():
                    errors.append(f"{path}: 缺字段注释 {line}")
    if errors:
        raise SystemExit("\n".join(errors))
    print(f"PASS documents={len(docs)}, repository_links={links}, AC=50, unique_tasks={len(tasks)}; structure only")

if __name__ == "__main__":
    main()
