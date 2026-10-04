#!/usr/bin/env python3
"""从真实作业表和控制台导航导出Auth目录；未审查的新入口不能自动取得资源范围。"""
import argparse
import json
from pathlib import Path
import re


RESOURCE_TYPES = {"wms_warehouse", "wms_enterprise"}
CODE = re.compile(r"[a-z][a-z0-9._-]{0,99}")
PATH = re.compile(r"/api/wms/v1/[A-Za-z0-9_/{}/-]+")
# 这里声明菜单对应的真实操作权限，不声明用户、角色或授权数据。
MENU_SCOPES = {
    "": {"masterdata.read", "stock.read", "inbound.read", "outbound.read", "fulfillment.read"},
    "catalog": {"masterdata.read"},
    "inbound": {"inbound.read"},
    "stock": {"stock.read", "inventory.read", "stock.audit"},
    "fulfillment": {"fulfillment.read", "outbound.read"},
    "transfers": {"transfer.read"},
    "counts": {"count.read"},
    "receive": {"inbound.receive"},
    "pick": {"outbound.pick"},
    "ship": {"outbound.ship"},
    "jobs": {"job.read", "task.read"},
    "recon": {"recon.read"},
}
GROUP_IDS = {"": "overview", "catalog": "in_out", "transfers": "collaboration", "jobs": "control"}


def tsv(text, columns):
    """严格读取有界契约，重复入口或畸形字段必须在发布前失败。"""
    result = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        row = line.split("\t")
        if len(row) != columns or row[0] not in {"GET", "POST"} or not PATH.fullmatch(row[1]):
            raise ValueError("作业表字段或路由无效")
        if not re.fullmatch(r"[a-z][A-Za-z0-9]*(?:\.[a-z][A-Za-z0-9]*)+", row[2]):
            raise ValueError("既有scope编码无效")
        key = (row[0], row[1])
        if key in result:
            raise ValueError("作业表包含重复入口")
        result[key] = row
    if not result or len(result) > 500:
        raise ValueError("作业表为空或超限")
    return result


def bind_operations(source_text, bindings_text):
    """每条新入口必须有Owner审查的资源绑定，不能靠路径前缀默认为全企业。"""
    source, bindings = tsv(source_text, 3), tsv(bindings_text, 6)
    if source.keys() != bindings.keys():
        raise ValueError("公开入口与中央资源绑定不一致")
    result, meanings = [], {}
    for key in sorted(source):
        method, path, scope = source[key]
        _, _, legacy, resource, capability, phase = bindings[key]
        if legacy != scope or resource not in RESOURCE_TYPES or not CODE.fullmatch(capability):
            raise ValueError("中央能力或资源绑定无效")
        if not capability.startswith("wms.") or phase not in {"W04", "W05", "W06"}:
            raise ValueError("中央能力命名空间或切片无效")
        meaning = (scope, resource)
        if capability in meanings and meanings[capability] != meaning:
            raise ValueError("同一能力编码具有冲突语义")
        meanings[capability] = meaning
        result.append({"method": method, "path": path, "legacy_scope": scope,
                       "capability": capability, "resource_type": resource, "slice": phase})
    return result


def read_navigation(text):
    """只解析本项目当前有限声明形式；源码结构变化时要求显式更新导出器。"""
    if "export const NAV_GROUPS: NavGroup[] = [" not in text:
        raise ValueError("控制台导航声明缺失")
    source = text.split("export const NAV_GROUPS: NavGroup[] = [", 1)[1]
    groups, current = [], None
    for line in source.splitlines():
        title = re.search(r'title: "([^"\n]+)"', line)
        if title:
            current = {"title": title.group(1), "items": []}
            groups.append(current)
        item = re.search(r'\{ to: "([^"\n]*)", label: "([^"\n]+)"([^}]*)\}', line)
        if item:
            if current is None:
                raise ValueError("菜单缺少分组")
            current["items"].append({"to": item.group(1), "label": item.group(2),
                                     "pda": bool(re.search(r"\bpda: true\b", item.group(3)))})
        elif re.search(r'\bto:\s*"', line):
            raise ValueError("导航声明格式变化，必须更新导出器")
    routes = [item["to"] for group in groups for item in group["items"]]
    if len(groups) != len(GROUP_IDS) or set(routes) != set(MENU_SCOPES) or len(routes) != len(set(routes)):
        raise ValueError("控制台菜单与中央菜单绑定不一致")
    if any(not group["items"] or group["items"][0]["to"] not in GROUP_IDS for group in groups):
        raise ValueError("菜单分组缺少稳定标识")
    return groups


def build(root):
    """只生成应用事实，无数据库写入、账号创建、角色授予或远端发布。"""
    operations = bind_operations(
        (root / "wms-security/src/main/resources/wms-operation-scopes.tsv").read_text(),
        (root / "docs/iam/operation-bindings.tsv").read_text())
    capabilities = {}
    for operation in operations:
        code = operation["capability"]
        entry = capabilities.setdefault(code, {"code": code, "resource_type": operation["resource_type"], "risk_level": "NORMAL"})
        # 非幂等读取形式的作业由Owner发布为独立高风险能力，不给已有管理员自动扩权。
        if operation["method"] != "GET":
            entry["risk_level"] = "HIGH"
    menus = []
    for group in read_navigation((root / "wms-console/src/shell/nav.tsx").read_text()):
        parent = "wms.nav.group." + GROUP_IDS[group["items"][0]["to"]]
        menus.append({"code": parent, "parent": None, "route": None, "any_of": [],
                      "label": group["title"], "position": len(menus)})
        for item in group["items"]:
            required = sorted({operation["capability"] for operation in operations
                               if operation["legacy_scope"] in MENU_SCOPES[item["to"]]})
            if not required:
                raise ValueError("菜单没有真实能力关联")
            menus.append({"code": "wms.nav." + (item["to"] or "overview"), "parent": parent,
                          "route": "/" + ("pda/" if item["pda"] else "") + item["to"],
                          "any_of": required, "label": item["label"], "position": len(menus)})
    if len(capabilities) > 200 or len(menus) > 100:
        raise ValueError("应用目录超过Auth协议上限")
    return {
        "catalog.json": {"schema_version": "1", "application": "wms", "manifest_version": 1,
                         "capabilities": sorted(capabilities.values(), key=lambda entry: entry["code"]), "menus": menus},
        "operations.json": {"schema_version": "1", "application": "wms", "operations": operations},
    }


def encoded(value):
    """稳定序列化，令同版目录可重复核对并保留真实中文名称。"""
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def main():
    """显式选择生成或只读核对；不通过命令行接收任何密钥。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args()
    artifacts = build(args.root)
    for name, data in artifacts.items():
        path = args.root / "docs/iam" / name
        content = encoded(data)
        if args.check:
            if not path.is_file() or path.read_text() != content:
                raise ValueError("中央目录产物缺失或漂移：" + name)
        else:
            path.write_text(content)
    print("PASS: WMS目录源与绑定一致；操作=%d，能力=%d，菜单=%d" % (
        len(artifacts["operations.json"]["operations"]), len(artifacts["catalog.json"]["capabilities"]),
        len(artifacts["catalog.json"]["menus"])))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        raise SystemExit("FAIL: " + str(error))
