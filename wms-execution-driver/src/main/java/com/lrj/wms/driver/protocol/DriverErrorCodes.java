package com.lrj.wms.driver.protocol;

/** 对外错误码集中定义，避免业务层拼字符串。 */
public final class DriverErrorCodes {
    public static final String CAPABILITY_NOT_SUPPORTED = "DRIVER_CAPABILITY_NOT_SUPPORTED";
    public static final String UNAVAILABLE = "DRIVER_UNAVAILABLE";
    public static final String DISABLED = "DRIVER_DISABLED";
    public static final String NOT_FOUND = "DRIVER_NOT_FOUND";
    public static final String DUPLICATE_ID = "DRIVER_DUPLICATE_ID";
    public static final String SELECTION_FAILED = "DRIVER_SELECTION_FAILED";
    public static final String POLICY_DENIED = "DRIVER_POLICY_DENIED";
    public static final String MANIFEST_INVALID = "DRIVER_MANIFEST_INVALID";
    public static final String TIMEOUT = "DRIVER_TIMEOUT";
    public static final String EXECUTION_FAILED = "DRIVER_EXECUTION_FAILED";
    public static final String CURSOR_CLI_NOT_FOUND = "CURSOR_CLI_NOT_FOUND";

    private DriverErrorCodes() {}
}
