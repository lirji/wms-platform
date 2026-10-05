"""验证权限目录的公开契约边界；测试不代表运行中的WMS已经接入中央授权。"""
import copy
import importlib.util
import re
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("auth_catalog", ROOT / "scripts/export-auth-catalog.py")
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


class AuthCatalogTest(unittest.TestCase):
    def test_covers_real_operations_and_separates_enterprise_and_warehouse_capabilities(self):
        artifacts = catalog.build(ROOT)
        operations = artifacts["operations.json"]["operations"]
        self.assertEqual(94, len(operations))
        self.assertEqual(43, len({operation["legacy_scope"] for operation in operations}))
        self.assertEqual(49, len(artifacts["catalog.json"]["capabilities"]))
        by_path = {(operation["method"], operation["path"]): operation for operation in operations}
        warehouse = by_path[("GET", "/api/wms/v1/warehouses")]
        enterprise = by_path[("GET", "/api/wms/v1/skus")]
        self.assertEqual("wms_warehouse", warehouse["resource_type"])
        self.assertEqual("wms_enterprise", enterprise["resource_type"])
        self.assertNotEqual(warehouse["capability"], enterprise["capability"])
        self.assertEqual("wms.stock.release_hold", by_path[("POST", "/api/wms/v1/warehouses/{warehouseId}/stock-holds/{holdId}/releases")]["capability"])
        self.assertEqual("wms_warehouse", by_path[("GET", "/api/wms/v1/transfers")]["resource_type"])

    def test_new_duplicate_or_changed_public_operation_requires_an_explicit_owner_binding(self):
        source = (ROOT / "wms-security/src/main/resources/wms-operation-scopes.tsv").read_text()
        bindings = (ROOT / "docs/iam/operation-bindings.tsv").read_text()
        for changed in [source + "GET\t/api/wms/v1/new-sensitive-report\tstock.read\n",
                        source + "GET\t/api/wms/v1/inventory\tstock.read\n",
                        source.replace("stock.read", "stock.write", 1)]:
            with self.subTest(source=changed[-65:]), self.assertRaises(ValueError):
                catalog.bind_operations(changed, bindings)
        with self.assertRaises(ValueError):
            catalog.bind_operations(source, bindings.replace("wms_warehouse", "store", 1))
        with self.assertRaises(ValueError):
            catalog.bind_operations(source, bindings.replace("wms.stock.read", "commerce.stock.read", 1))

    def test_real_menu_labels_routes_hierarchy_and_write_risk_are_consistent(self):
        manifest = catalog.build(ROOT)["catalog.json"]
        menus = {menu["code"]: menu for menu in manifest["menus"]}
        capabilities = {capability["code"]: capability for capability in manifest["capabilities"]}
        self.assertEqual(16, len(menus))
        self.assertEqual("PDA 发运", menus["wms.nav.ship"]["label"])
        self.assertEqual("/pda/ship", menus["wms.nav.ship"]["route"])
        for menu in menus.values():
            self.assertTrue(set(menu["any_of"]).issubset(capabilities))
            if menu["parent"]:
                self.assertIn(menu["parent"], menus)
            if menu["route"]:
                self.assertTrue(menu["any_of"])
        self.assertEqual("HIGH", capabilities["wms.adjustment.approve"]["risk_level"])
        self.assertEqual("HIGH", capabilities["wms.adjustment.apply"]["risk_level"])
        self.assertEqual("NORMAL", capabilities["wms.stock.read"]["risk_level"])

    def test_unknown_menu_and_conflicting_capability_meanings_fail_closed(self):
        text = (ROOT / "wms-console/src/shell/nav.tsx").read_text()
        with self.assertRaises(ValueError):
            catalog.read_navigation(re.sub(r"to: (['\"])ship\1", "to: 'admin'", text))
        source = (ROOT / "wms-security/src/main/resources/wms-operation-scopes.tsv").read_text()
        bindings = (ROOT / "docs/iam/operation-bindings.tsv").read_text()
        with self.assertRaises(ValueError):
            catalog.bind_operations(source, bindings.replace("wms.masterdata.read.enterprise", "wms.masterdata.read"))

    def test_navigation_formatting_preserves_facts_and_rejects_unparsed_or_duplicate_fields(self):
        text = (ROOT / "wms-console/src/shell/nav.tsx").read_text()
        expected = catalog.read_navigation(text)
        compact = re.sub(r"\s+", " ", text).replace("'", '"')
        multiline = text.replace("to:", "\n to:").replace("label:", "\n label:")
        self.assertEqual(expected, catalog.read_navigation(compact))
        self.assertEqual(expected, catalog.read_navigation(multiline))
        for changed in [text.replace("items: [", "items: [ ...hidden,", 1),
                        text.replace("pda: true", "pda: true, pda: false", 1),
                        text.replace("pda: true", "pda: isPda", 1),
                        text.replace("to: 'ship'", "to: 'pick'")]:
            with self.subTest(changed=changed[-200:]), self.assertRaises(ValueError):
                catalog.read_navigation(changed)

    def test_artifact_replay_is_identical_and_check_rejects_drift_without_rewriting(self):
        first = catalog.build(ROOT)
        self.assertEqual(catalog.encoded(first), catalog.encoded(catalog.build(ROOT)))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in ["wms-security/src/main/resources/wms-operation-scopes.tsv",
                             "docs/iam/operation-bindings.tsv", "wms-console/src/shell/nav.tsx"]:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text((ROOT / relative).read_text())
            command = ["python3", str(ROOT / "scripts/export-auth-catalog.py"), "--root", str(root)]
            subprocess.run(command + ["--write"], check=True, capture_output=True)
            subprocess.run(command + ["--check"], check=True, capture_output=True)
            # 各消费产物独立漂移也必须拒绝，不能只保护发布目录而放过运行绑定。
            for relative in ["wms-security/src/main/resources/wms-central-operation-bindings.tsv",
                             "wms-security/src/main/resources/wms-central-catalog.json",
                             "wms-console/src/auth/centralBindings.ts"]:
                runtime = root / relative
                original = runtime.read_bytes()
                runtime.write_bytes(original + b"drift")
                self.assertNotEqual(0, subprocess.run(command + ["--check"], capture_output=True).returncode)
                self.assertEqual(original + b"drift", runtime.read_bytes())
                runtime.write_bytes(original)
            target = root / "docs/iam/catalog.json"
            changed = copy.deepcopy(first["catalog.json"])
            changed["menus"][0]["label"] = "漂移"
            target.write_text(catalog.encoded(changed))
            before = target.read_bytes()
            rejected = subprocess.run(command + ["--check"], capture_output=True)
            self.assertNotEqual(0, rejected.returncode)
            self.assertEqual(before, target.read_bytes())


if __name__ == "__main__":
    unittest.main()
