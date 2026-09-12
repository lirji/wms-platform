package com.lrj.wms.inventory.query;

import com.lrj.wms.inventory.count.CountMapper;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.runtime.web.CursorPage;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 MySQL 回归相同时间戳跨页、不重不漏，以及 SQL 在分页前执行仓权限过滤。 */
class BoundedPaginationIT {
    @Test void pagesEqualTimestampsWithoutDuplicatesAndFiltersUnauthorizedWarehouses() {
        try (var mysql = new MySQLContainer("mysql:8.4.11")) {
            mysql.start();
            var ds = new MysqlDataSource(); ds.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC")); ds.setUser(mysql.getUsername()); ds.setPassword(mysql.getPassword());
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(ds);
            var now = Timestamp.valueOf("2026-09-12 00:00:00.123456");
            for (int i = 0; i < 205; i++) jdbc.update("INSERT INTO count_plan (id,enterprise_id,warehouse_id,status,reason_code,created_at,updated_at) VALUES (?,?,?,'DRAFT','CYCLE',?,?)", String.format("P%03d", i), "ENT", "WH", now, now);
            jdbc.update("INSERT INTO count_plan (id,enterprise_id,warehouse_id,status,reason_code,created_at,updated_at) VALUES ('foreign','OTHER','WH','DRAFT','CYCLE',?,?)", now, now);
            for (String id : List.of("A", "B", "C")) jdbc.update("INSERT INTO warehouse (id,enterprise_id,warehouse_id,code,name,timezone,state,created_at,updated_at) VALUES (?,'ENT',?,?,?,'UTC','ACTIVE',?,?)", id, id, id, id, now, now);
            var config = new Configuration(new Environment("page", new JdbcTransactionFactory(), ds));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
            config.addMapper(CountMapper.class); config.addMapper(MasterdataMapper.class);
            var sessions = new SqlSessionFactoryBuilder().build(config);
            List<String> ids = new ArrayList<>(); String cursor = null;
            do {
                var page = CursorPage.chronological(50, cursor, "count:ENT:WH");
                try (var session = sessions.openSession()) {
                    var fetched = session.getMapper(CountMapper.class).listPlansPage("ENT", "WH", page);
                    assertTrue(fetched.size() <= 51);
                    var result = page.result(fetched, true);
                    ((List<Map<String, Object>>) result.get("items")).forEach(row -> ids.add((String) row.get("id")));
                    cursor = (String) result.get("nextCursor");
                }
            } while (cursor != null);
            assertEquals(205, ids.size()); assertEquals(205, new HashSet<>(ids).size());
            assertEquals("P204", ids.getFirst()); assertEquals("P000", ids.getLast());
            try (var session = sessions.openSession()) {
                var page = CursorPage.parse(1, null, "wh");
                var rows = session.getMapper(MasterdataMapper.class).listWarehouses("ENT", page, Set.of("C"));
                assertEquals(List.of("C"), rows.stream().map(row -> row.get("id")).toList());
                assertTrue(session.getMapper(MasterdataMapper.class).listWarehouses("ENT", page, Set.of()).isEmpty());
            }
        }
    }
}
