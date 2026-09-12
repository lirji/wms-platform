package com.lrj.wms.probe;

import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** failure-it 读取库存迁移目录，不依赖 Boot 瘦/胖包。 */
final class InventoryMigrationSupport {
    private InventoryMigrationSupport() {
    }

    static void migrate(javax.sql.DataSource source) {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path fromModule = cwd.resolve("../wms-inventory/src/main/resources/db/migration").normalize();
        Path fromRoot = cwd.resolve("wms-inventory/src/main/resources/db/migration");
        Path migrations = Files.isDirectory(fromModule) ? fromModule : fromRoot;
        assertTrue(Files.isDirectory(migrations), "缺少库存迁移目录：" + migrations);
        Flyway.configure().dataSource(source).locations("filesystem:" + migrations).load().migrate();
    }
}
