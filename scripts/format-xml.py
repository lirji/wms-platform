#!/usr/bin/env python3
"""按现有两空格风格整理 reactor XML，并保护 Mapper 的 SQL 文本语义。"""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET


def signature(data):
    """只忽略 XML 元素间的空白，SQL/属性/元素顺序必须完全一致。"""
    def element(node):
        text = node.text if node.text and node.text.strip() else None
        tail = node.tail if node.tail and node.tail.strip() else None
        return (node.tag, sorted(node.attrib.items()), text, tail,
                tuple(element(child) for child in node))
    return element(ET.fromstring(data))


def main():
    """仅操作已登记模块的 pom 和源码 XML；不重写已执行的数据库迁移。"""
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if shutil.which("xmllint") is None:
        parser.error("需要 xmllint：Linux 安装 libxml2-utils，macOS 使用系统 libxml2")
    repo = Path(__file__).resolve().parents[1]
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    modules = [n.text for n in ET.parse(repo / "pom.xml").findall("m:modules/m:module", ns)]
    files = [repo / "pom.xml"]
    for module in modules:
        files.append(repo / module / "pom.xml")
        files.extend((repo / module / "src").glob("**/*.xml"))
    changed = []
    for path in sorted(files):
        before = path.read_bytes()
        after = subprocess.check_output(
            ["xmllint", "--nonet", "--format", "--encode", "UTF-8", "-"],
            input=before, env={**os.environ, "XMLLINT_INDENT": "  "})
        if signature(before) != signature(after):
            raise RuntimeError(f"XML 语义发生变化，停止处理：{path.relative_to(repo)}")
        if before != after:
            changed.append(str(path.relative_to(repo)))
            if args.write:
                path.write_bytes(after)
    if changed and args.check:
        print("\n".join(changed))
        raise SystemExit(1)
    print(f"PASS: {len(files)} 个 XML 的格式与语义核对完成")


if __name__ == "__main__":
    main()
