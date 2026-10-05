package com.lrj.wms.driver.manifest;

import com.lrj.wms.driver.DriverException;
import com.lrj.wms.driver.protocol.DriverErrorCodes;

import java.util.Map;

public class ManifestValidationException extends DriverException {
    public ManifestValidationException(String message) {
        super(DriverErrorCodes.MANIFEST_INVALID, message, Map.of());
    }
}
