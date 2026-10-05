"""复现拆包时真实的装载失败和依赖倒置风险。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("module_packages", Path(__file__).parents[1] / "check-module-packages.py")
packages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packages)


class ModulePackagesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"><modules><module>wms-probe</module></modules></project>')

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def test_namespace_and_resource_must_follow_the_moved_interface(self):
        self.write("wms-probe/src/main/java/example/persistence/ProbeMapper.java", "package example.persistence; public interface ProbeMapper {}")
        self.write("wms-probe/src/main/resources/example/persistence/ProbeMapper.xml", '<mapper namespace="example.persistence.ProbeMapper"/>')
        self.assertEqual([], packages.check(self.root))
        self.write("wms-probe/src/main/resources/example/persistence/ProbeMapper.xml", '<mapper namespace="example.old.ProbeMapper"/>')
        errors = packages.check(self.root)
        self.assertTrue(any("无对应接口" in error for error in errors))
        self.assertTrue(any("资源目录" in error for error in errors))

    def test_domain_cannot_import_jdbc_but_persistence_can(self):
        self.write("wms-probe/src/main/java/example/domain/Expiry.java", "package example.domain;\nimport java.sql.Timestamp;\npublic class Expiry {}")
        self.write("wms-probe/src/main/java/example/persistence/Projection.java", "package example.persistence;\nimport java.sql.Timestamp;\npublic class Projection {}")
        errors = packages.check(self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("领域依赖", errors[0])

    def test_java_directory_and_autoconfiguration_cannot_keep_old_names(self):
        self.write("wms-probe/src/main/java/example/Old.java", "package example.newname; public class Old {}")
        self.write("wms-probe/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports", "example.Missing\n")
        errors = packages.check(self.root)
        self.assertTrue(any("package 与目录" in error for error in errors))
        self.assertTrue(any("自动配置类不存在" in error for error in errors))
