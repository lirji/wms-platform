package com.lrj.wms.inventory.query;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** XML 迁移回归：不必启动数据库即可校验映射资源、语句标识与返回元素类型。 */
class MapperXmlBindingTest {
    @Test void everyInventoryAndSourceMapperLoadsWithCorrectResultType() throws Exception {
        for (String module : List.of(".", "../wms-inbound", "../wms-outbound", "../wms-fulfillment")) {
            Path root = Path.of(module, "src/main/java");
            try (var paths = Files.walk(root)) {
                for (Path source : paths.filter(p -> p.toString().endsWith("Mapper.java")).toList()) {
                    String className = root.relativize(source).toString().replace('/', '.').replace(".java", "");
                    Class<?> mapper = Class.forName(className);
                    var config = new Configuration();
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
                    config.addMapper(mapper);
                    config.getMappedStatementNames();
                    for (var method : mapper.getDeclaredMethods()) {
                        if (method.isDefault() || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                        String id = className + "." + method.getName();
                        assertTrue(config.hasStatement(id), id);
                        if (method.getReturnType() != List.class) continue;
                        var element = ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
                        Class<?> expected = element instanceof ParameterizedType type ? (Class<?>) type.getRawType() : (Class<?>) element;
                        assertTrue(expected.isAssignableFrom(config.getMappedStatement(id).getResultMaps().getFirst().getType()), id);
                    }
                }
            }
        }
    }
}
