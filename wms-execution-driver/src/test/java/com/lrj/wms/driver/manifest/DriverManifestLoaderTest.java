package com.lrj.wms.driver.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lrj.wms.driver.protocol.DesiredStatus;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.RiskClass;

import org.junit.jupiter.api.Test;

import java.util.List;

class DriverManifestLoaderTest {
    private final DriverManifestLoader loader = new DriverManifestLoader();

    @Test
    void validManifestLoads() {
        List<DriverManifest> manifests = loader.loadDefault();
        DriverManifest codex =
                manifests.stream()
                        .filter(item -> "codex-local".equals(item.driverId()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("codex local CLI adapter", codex.displayName());
        assertEquals(DesiredStatus.ACTIVE, codex.status());
        assertEquals(DriverManifestLoader.PROTOCOL, codex.protocol());
        assertTrue(codex.capabilities().contains(DriverAction.READ_REPO));
        assertTrue(codex.riskClasses().contains(RiskClass.NORMAL));
        assertEquals("native", codex.adapters().getFirst());
    }

    @Test
    void cursorLocalManifestLoads() {
        DriverManifest cursor =
                loader.loadDefault().stream()
                        .filter(item -> "cursor-local".equals(item.driverId()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("Cursor Local CLI Driver", cursor.displayName());
        assertEquals(DesiredStatus.ACTIVE, cursor.status());
        assertTrue(cursor.capabilities().contains(DriverAction.EXECUTE_TESTS));
        assertEquals("agent", cursor.command().executable());
        assertTrue(cursor.command().candidates().contains("cursor-agent"));
    }

    @Test
    void illegalProtocolIsRejected() {
        ManifestValidationException error =
                assertThrows(
                        ManifestValidationException.class,
                        () ->
                                loader.parse(
                                        """
                {"protocol":"execution-driver-manifest/v0","driver_id":"codex-local","display_name":"x","status":"active",
                 "adapters":["native"],"risk_classes":["NORMAL"],"capabilities":["read_repo"],
                 "command":{"executable":"codex"}}
                """));
        assertTrue(error.getMessage().contains("非法protocol"));
    }

    @Test
    void unknownActionIsRejected() {
        ManifestValidationException error =
                assertThrows(
                        ManifestValidationException.class,
                        () ->
                                loader.parse(
                                        """
                {"protocol":"execution-driver-manifest/v1","driver_id":"codex-local","display_name":"x","status":"active",
                 "adapters":["native"],"risk_classes":["NORMAL"],"capabilities":["teleport"],
                 "command":{"executable":"codex"}}
                """));
        assertTrue(error.getMessage().contains("未知action"));
    }

    @Test
    void unknownRiskClassIsRejected() {
        ManifestValidationException error =
                assertThrows(
                        ManifestValidationException.class,
                        () ->
                                loader.parse(
                                        """
                {"protocol":"execution-driver-manifest/v1","driver_id":"codex-local","display_name":"x","status":"active",
                 "adapters":["native"],"risk_classes":["NUCLEAR"],"capabilities":["read_repo"],
                 "command":{"executable":"codex"}}
                """));
        assertTrue(error.getMessage().contains("未知riskClass"));
    }
}
