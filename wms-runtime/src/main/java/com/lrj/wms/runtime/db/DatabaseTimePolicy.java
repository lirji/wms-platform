package com.lrj.wms.runtime.db;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 每个物理库固定墙钟存储偏移；HTTP/消息统一转换 Instant。旧库必须显式声明历史依据，不自动改写数据。 */
@ConfigurationProperties("wms.runtime.db.time")
public record DatabaseTimePolicy(@DefaultValue("UTC") String storageZone, @DefaultValue("") String legacyEvidence) {
    public DatabaseTimePolicy {
        if (storageZone == null || !(ZoneId.of(storageZone).normalized() instanceof ZoneOffset offset)
                || Math.abs(offset.getTotalSeconds()) > 14 * 3600 || offset.getTotalSeconds() % 60 != 0)
            throw new IllegalArgumentException("DATETIME存储需UTC或明确固定偏移；含夏令时历史必须先审计转换");
        if (legacyEvidence == null || legacyEvidence.length() > 256) throw new IllegalArgumentException("历史时间依据引用无效");
    }
    /** 数字偏移无需依赖 MySQL 时区表；会话与 JDBC 使用同一个权威值。 */
    public String offset() {
        String id = ZoneId.of(storageZone).normalized().getId();
        return "Z".equals(id) ? "+00:00" : id;
    }
    /** 迁移前检查历史声明，迁移后原子固定语义；重复启动不能静默切换偏移。 */
    public void initialize(DataSource source, Runnable migrations) {
        var config = new Configuration(new Environment("database-time", new JdbcTransactionFactory(),source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.setDefaultStatementTimeout(5);
        config.addMapper(DatabaseTimeMapper.class);
        var sessions = new SqlSessionFactoryBuilder().build(config);
        String evidence;
        try (var session = sessions.openSession()) {
            var mapper = session.getMapper(DatabaseTimeMapper.class);
            if (!offset().equals(mapper.sessionOffset())) throw new IllegalStateException("数据源会话时区未按数据库时间策略配置");
            Map<String,Object> current = mapper.policyTableExists() == 0 ? null : mapper.policy();
            if (current != null) { requireOffset(current); evidence = String.valueOf(current.get("evidence_ref")); }
            else if (mapper.existingTables() > 0) {
                if (legacyEvidence.isBlank()) throw new IllegalStateException("已有数据库缺少时区来源记录；需配置已核实的 wms.runtime.db.time.storage-zone 和 legacy-evidence");
                evidence = legacyEvidence;
            } else evidence = "fresh-schema";
        }
        migrations.run();
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(DatabaseTimeMapper.class);
            mapper.register(offset(),evidence);
            requireOffset(mapper.policy());
            session.commit();
        }
    }
    private void requireOffset(Map<String,Object> row) {
        if (!offset().equals(row.get("storage_offset"))) throw new IllegalStateException("配置偏移与数据库时区记录冲突；必须先执行经审计的数据转换，不能热切换");
    }
}
