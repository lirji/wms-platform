#!/usr/bin/env python3
"""Fail if required JUnit cases are missing, skipped, or not successful."""
from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_required(path: Path) -> list[str]:
    items = []
    for line in path.read_text().splitlines():
        text = line.strip()
        if not text or text.startswith("#"):
            continue
        items.append(text)
    return items


def collect_cases(report_dirs: list[Path]) -> dict[str, str]:
    found: dict[str, str] = {}
    for directory in report_dirs:
        if not directory.is_dir():
            continue
        for xml_path in directory.rglob("TEST-*.xml"):
            root = ET.parse(xml_path).getroot()
            for case in root.findall("testcase"):
                classname = case.attrib.get("classname", "")
                name = case.attrib.get("name", "")
                key = f"{classname}#{name}"
                if case.find("skipped") is not None:
                    found[key] = "skipped"
                elif case.find("failure") is not None or case.find("error") is not None:
                    found[key] = "failed"
                else:
                    found[key] = "passed"
    return found


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", choices=("default", "failure"), required=True)
    args = parser.parse_args()
    required_file = ROOT / f"scripts/required-its-{args.suite}.txt"
    required = load_required(required_file)
    if args.suite == "failure":
        report_dirs = [ROOT / "wms-test-support/target/failsafe-reports"]
    else:
        report_dirs = [
            ROOT / "wms-inventory/target/surefire-reports",
            ROOT / "wms-inventory/target/failsafe-reports",
            ROOT / "wms-fulfillment/target/failsafe-reports",
            ROOT / "wms-inbound/target/failsafe-reports",
            ROOT / "wms-outbound/target/failsafe-reports",
        ]
    found = collect_cases(report_dirs)
    errors = []
    for key in required:
        status = found.get(key)
        if status is None:
            errors.append(f"missing {key}")
        elif status != "passed":
            errors.append(f"{status} {key}")
    if errors:
        print("required IT gate failed:", file=sys.stderr)
        for error in errors:
            print(f"  {error}", file=sys.stderr)
        return 1
    print(f"required IT gate passed: {len(required)} {args.suite} cases")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
