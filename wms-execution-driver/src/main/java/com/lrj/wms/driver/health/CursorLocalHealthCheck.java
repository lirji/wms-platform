package com.lrj.wms.driver.health;

import com.lrj.wms.driver.adapter.NativeCursorAdapter;
import com.lrj.wms.driver.registry.RegisteredDriver;

import java.nio.file.Path;

/** cursor-local 专用健康检查，Cursor 探测细节留在 native adapter。 */
public final class CursorLocalHealthCheck {
    private final NativeCursorAdapter adapter;

    public CursorLocalHealthCheck(NativeCursorAdapter adapter) {
        this.adapter = adapter;
    }

    public HealthReport check(RegisteredDriver driver, Path workspace) {
        return adapter.health(driver, workspace);
    }
}
