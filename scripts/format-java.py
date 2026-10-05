#!/usr/bin/env python3
"""使用固定摘要的独立 Java 格式化工具，保持开发机和 CI 的格式结果一致。"""

import argparse
import hashlib
from pathlib import Path
import subprocess
import urllib.request
import xml.etree.ElementTree as ET

VERSION = "1.37.0"
SHA256 = "834b2a0c38cb774953322a84b5ca3f2f40dd3156650b3cd44d3b744345962f7a"
URL = (
    "https://github.com/google/google-java-format/releases/download/"
    f"v{VERSION}/google-java-format-{VERSION}-all-deps.jar"
)


def main():
    """只处理 reactor 的人工 Java 源码，不改生成目录或数据库迁移。"""
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    parser.add_argument("--module", action="append", help="只检查指定的 reactor 模块")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    modules = [n.text for n in ET.parse(repo / "pom.xml").findall("m:modules/m:module", ns)]
    selected = args.module or modules
    if any(m not in modules for m in selected):
        parser.error("module 必须来自当前 reactor，不能指定工作区外的路径")
    files = sorted(p for m in selected for p in (repo / m / "src").glob("**/*.java"))
    if not files:
        parser.error("所选模块没有人工 Java 源码")
    cache = repo / ".local" / "tools" / "google-java-format"
    cache.mkdir(parents=True, exist_ok=True)
    jar = cache / f"google-java-format-{VERSION}-all-deps.jar"
    if not jar.exists():
        # 摘要来自官方 release 元数据；下载失败或内容变更均不执行未知制品。
        with urllib.request.urlopen(URL, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != SHA256:
            raise RuntimeError("格式化工具摘要与已选定的官方制品不符")
        jar.write_bytes(data)
    if hashlib.sha256(jar.read_bytes()).hexdigest() != SHA256:
        raise RuntimeError("本地格式化工具摘要不符，请核对缓存，不自动覆盖")
    flags = ["--replace"] if args.write else ["--dry-run", "--set-exit-if-changed"]
    # 四空格沿用已有可读代码；避免格式整理改写长协议字面量和中文说明。
    command = [
        "java", "-Xmx512m", "-XX:ActiveProcessorCount=2", "-jar", str(jar),
        "--aosp", "--skip-reflowing-long-strings", "--skip-javadoc-formatting", *flags,
        *(str(p) for p in files),
    ]
    subprocess.run(command, check=True, cwd=repo)


if __name__ == "__main__":
    main()
