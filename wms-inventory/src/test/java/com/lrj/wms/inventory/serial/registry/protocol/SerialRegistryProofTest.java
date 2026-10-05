package com.lrj.wms.inventory.serial.registry.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lrj.wms.inventory.serial.registry.error.SerialRegistryUnavailableException;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/** 提取共享凭证规则之前固定边界：历史证明必须完整匹配范围与整数代际。 */
class SerialRegistryProofTest {
    private static Map<String, Object> shipment() {
        return new HashMap<>(
                Map.of(
                        "schemaVersion",
                        1,
                        "enterpriseId",
                        "ENT",
                        "warehouseId",
                        "WH",
                        "skuId",
                        "SKU",
                        "normalizedSerial",
                        "SN",
                        "shipmentRef",
                        "SHIP",
                        "ownerEpoch",
                        3L));
    }

    private static Map<String, Object> release() {
        return new HashMap<>(
                Map.of(
                        "enterpriseId",
                        "ENT",
                        "sourceWarehouseId",
                        "WH",
                        "skuId",
                        "SKU",
                        "normalizedSerial",
                        "SN",
                        "transferId",
                        "TRANSFER",
                        "sourceReleaseRef",
                        "RELEASE",
                        "fromEpoch",
                        3L));
    }

    @Test
    void shipmentProofRejectsMissingMismatchedAndCoercedFields() {
        assertDoesNotThrow(
                () ->
                        SerialRegistryProof.requireShipment(
                                Map.of("shipment", shipment()),
                                "ENT",
                                "WH",
                                "SKU",
                                "SN",
                                "SHIP",
                                3));
        for (String key : shipment().keySet()) {
            var missing = shipment();
            missing.remove(key);
            assertThrows(
                    SerialRegistryUnavailableException.class,
                    () ->
                            SerialRegistryProof.requireShipment(
                                    Map.of("shipment", missing),
                                    "ENT",
                                    "WH",
                                    "SKU",
                                    "SN",
                                    "SHIP",
                                    3));
            var changed = shipment();
            changed.put(key, "wrong");
            assertThrows(
                    SerialRegistryUnavailableException.class,
                    () ->
                            SerialRegistryProof.requireShipment(
                                    Map.of("shipment", changed),
                                    "ENT",
                                    "WH",
                                    "SKU",
                                    "SN",
                                    "SHIP",
                                    3));
        }
        var fractional = shipment();
        fractional.put("ownerEpoch", 3.0);
        assertThrows(
                SerialRegistryUnavailableException.class,
                () ->
                        SerialRegistryProof.requireShipment(
                                Map.of("shipment", fractional),
                                "ENT",
                                "WH",
                                "SKU",
                                "SN",
                                "SHIP",
                                3));
    }

    @Test
    void releaseProofRejectsMissingMismatchedAndCoercedFields() {
        assertDoesNotThrow(
                () ->
                        SerialRegistryProof.requireRelease(
                                Map.of("sourceRelease", release()),
                                "ENT",
                                "WH",
                                "SKU",
                                "SN",
                                "TRANSFER",
                                "RELEASE",
                                3));
        for (String key : release().keySet()) {
            var missing = release();
            missing.remove(key);
            assertThrows(
                    SerialRegistryUnavailableException.class,
                    () ->
                            SerialRegistryProof.requireRelease(
                                    Map.of("sourceRelease", missing),
                                    "ENT",
                                    "WH",
                                    "SKU",
                                    "SN",
                                    "TRANSFER",
                                    "RELEASE",
                                    3));
            var changed = release();
            changed.put(key, "wrong");
            assertThrows(
                    SerialRegistryUnavailableException.class,
                    () ->
                            SerialRegistryProof.requireRelease(
                                    Map.of("sourceRelease", changed),
                                    "ENT",
                                    "WH",
                                    "SKU",
                                    "SN",
                                    "TRANSFER",
                                    "RELEASE",
                                    3));
        }
        var fractional = release();
        fractional.put("fromEpoch", 3.0);
        assertThrows(
                SerialRegistryUnavailableException.class,
                () ->
                        SerialRegistryProof.requireRelease(
                                Map.of("sourceRelease", fractional),
                                "ENT",
                                "WH",
                                "SKU",
                                "SN",
                                "TRANSFER",
                                "RELEASE",
                                3));
    }
}
