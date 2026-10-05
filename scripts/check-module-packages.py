#!/usr/bin/env python3
"""检查拆包后会影响实际运行的源码、Mapper 装载与领域依赖边界。"""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET


PACKAGE = re.compile(r"^package\s+([\w.]+)\s*;", re.MULTILINE)
IMPORT = re.compile(r"^import\s+(?:static\s+)?([\w.]+)", re.MULTILINE)
MAIN_SOURCE_SET = "main"
TEST_SOURCE_SET = "test"
SOURCE_SETS = (MAIN_SOURCE_SET, TEST_SOURCE_SET)
FORBIDDEN_DOMAIN = ("java.sql.", "javax.sql.", "java.net.http.",
                    "org.springframework.jdbc.", "org.apache.ibatis.",
                    "com.zaxxer.", "com.github.benmanes.caffeine.",
                    "org.springframework.data.redis.", "com.lrj.wms.runtime.db.")


def check(root: Path) -> list[str]:
    """只读正式 reactor；不把开发者未发布的独立模块纳入本任务。"""
    pom = ET.parse(root / "pom.xml").getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    modules = [root / node.text for node in pom.findall("m:modules/m:module", namespace)]
    errors = []
    sources = {}
    for module in modules:
        for kind in SOURCE_SETS:
            java = module / "src" / kind / "java"
            for path in sorted(java.rglob("*.java")):
                text = path.read_text()
                match = PACKAGE.search(text)
                if not match:
                    errors.append(f"{path.relative_to(root)}: 缺少 package")
                    continue
                package = match[1]
                expected = Path(*package.split(".")) / path.name
                if path.relative_to(java) != expected:
                    errors.append(f"{path.relative_to(root)}: package 与目录不一致")
                sources[(module, kind, f"{package}.{path.stem}")] = text
                if kind == MAIN_SOURCE_SET and "domain" in package.split("."):
                    for imported in IMPORT.findall(text):
                        if imported.startswith(FORBIDDEN_DOMAIN):
                            errors.append(f"{path.relative_to(root)}: 领域依赖基础设施 {imported}")
    for module in modules:
        for kind in SOURCE_SETS:
            resources = module / "src" / kind / "resources"
            for path in sorted(resources.rglob("*.xml")):
                node = ET.parse(path).getroot()
                if node.tag != "mapper":
                    continue
                name = node.get("namespace", "")
                source = sources.get((module, kind, name)) or sources.get((module, MAIN_SOURCE_SET, name))
                if source is None or not re.search(r"\binterface\s+" + re.escape(name.rsplit(".", 1)[-1]) + r"\b", source):
                    errors.append(f"{path.relative_to(root)}: Mapper namespace 无对应接口 {name}")
                if path.relative_to(resources) != Path(*name.split(".")).with_suffix(".xml"):
                    errors.append(f"{path.relative_to(root)}: Mapper 资源目录不匹配 namespace")
            imports = resources / "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
            if imports.exists():
                for line in imports.read_text().splitlines():
                    name = line.split("#", 1)[0].strip()
                    if name and (module, MAIN_SOURCE_SET, name) not in sources:
                        errors.append(f"{imports.relative_to(root)}: 自动配置类不存在 {name}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    root = parser.parse_args().root.resolve()
    errors = check(root)
    if errors:
        print("\n".join(errors))
        return 1
    print("module packages: PASS (源码目录、Mapper 装载、自动配置、领域依赖)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
