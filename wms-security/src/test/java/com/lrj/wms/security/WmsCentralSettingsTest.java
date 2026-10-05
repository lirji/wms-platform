package com.lrj.wms.security;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 私密配置和身份映射冲突必须启动失败，不允许静默选择旧令牌模式。 */
class WmsCentralSettingsTest {
    @TempDir Path directory;
    private String configuration() {
        return "central.base-url=http://127.0.0.1:18545\ncentral.service-credential=" + "s".repeat(48)
                + "\ncentral.tenant-id=" + UUID.randomUUID() + "\ncentral.enterprise-id=ENT-DEMO\ncentral.application=wms"
                + "\ncentral.environment=local\ncentral.organization=local-wms\ncentral.issuer=http://localhost:18090\ncentral.client-id=wms-central\n";
    }
    private WmsOidcProperties oidc() {
        var value = new WmsOidcProperties(); value.setIssuer("http://localhost:18090"); value.setClientId("wms-central"); return value;
    }
    private Path file(String body) throws Exception {
        Path path = Files.createTempFile(directory, "central", ".properties");
        Files.writeString(path, body); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); return path;
    }
    @Test void validConfigurationDoesNotExposeCredentials() throws Exception {
        var settings = WmsCentralSettings.read(file(configuration()).toString(), oidc());
        assertEquals("ENT-DEMO", settings.enterprise()); assertFalse(settings.toString().contains("s".repeat(48)));
    }
    @Test void duplicateKeysInvalidTenantAndOidcRebindingFail() throws Exception {
        String valid = configuration();
        for (String bad : java.util.List.of(valid + "central.application=commerce\n", valid.replace("central.application=wms", "central.application=commerce"),
                valid.replace("central.client-id=wms-central", "central.client-id=old-client"), valid.replace("central.maximum-concurrent", "unused") + "central.maximum-concurrent=100\n")) {
            assertThrows(IllegalStateException.class, () -> WmsCentralSettings.read(file(bad).toString(), oidc()));
        }
        assertThrows(IllegalStateException.class, () -> WmsCentralSettings.read(null, oidc()));
    }
    @Test void readableByOthersAndSymlinkFilesAreRejected() throws Exception {
        var target = file(configuration());
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IllegalStateException.class, () -> WmsCentralSettings.read(target.toString(), oidc()));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        Path link = directory.resolve("link"); Files.createSymbolicLink(link, target);
        assertThrows(IllegalStateException.class, () -> WmsCentralSettings.read(link.toString(), oidc()));
    }
    @Test void remotePlaintextHostCannotBeEnabled() throws Exception {
        var settings = WmsCentralSettings.read(file(configuration().replace("http://127.0.0.1:18545", "http://remote.example:18545")).toString(), oidc());
        assertThrows(IllegalArgumentException.class, () -> new WmsCentralAuthorization(settings));
    }
}
