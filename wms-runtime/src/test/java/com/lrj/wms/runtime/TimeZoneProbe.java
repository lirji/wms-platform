package com.lrj.wms.runtime;

import com.lrj.wms.runtime.db.*;
import com.lrj.wms.runtime.web.CursorPage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 独立JVM证明时区参数真实隔离，不能只在同一进程切换全局TimeZone。 */
public final class TimeZoneProbe {
    public static final Instant EVENT = Instant.parse("2026-11-01T09:30:00.123456Z");
    public static void main(String[] args) throws Exception {
        var policy=new DatabaseTimePolicy(System.getenv("PROBE_OFFSET"),"");
        try(var ds=RuntimeDataSources.create("time-probe",System.getenv("PROBE_URL"),System.getenv("PROBE_USER"),System.getenv("PROBE_PASSWORD"),
                new DatabaseBudget(2,0,1000,500,5,1000,10000),policy)) {
            policy.initialize(ds,()->{});
            var config=new Configuration(new Environment("time-probe",new JdbcTransactionFactory(),ds));
            DatabaseInstants.configure(config); config.addMapper(TimeProbeMapper.class);
            var factory=new SqlSessionFactoryBuilder().build(config);
            try(var session=factory.openSession(false)) {
                var mapper=session.getMapper(TimeProbeMapper.class);
                if("write".equals(args[0])) {
                    mapper.insert("A",Timestamp.from(EVENT)); mapper.insert("B",Timestamp.from(EVENT.plusSeconds(3600)));
                    var page=CursorPage.chronological(1,null,"time-probe").result(mapper.rows(),true);
                    Files.writeString(Path.of(System.getenv("PROBE_CURSOR")),String.valueOf(page.get("nextCursor")));
                    session.commit();
                } else if("read".equals(args[0])) {
                    var rows=mapper.rows();
                    check(DatabaseInstants.require(rows.getFirst().get("created_at")).equals(EVENT.plusSeconds(3600)),"UTC instant drift");
                    check("2026-11-01".equals(String.valueOf(rows.getFirst().get("business_date"))),"DATE drift");
                    var page=CursorPage.chronological(1,Files.readString(Path.of(System.getenv("PROBE_CURSOR"))),"time-probe");
                    var next=mapper.after(page);
                    check(next.size()==1 && "A".equals(next.getFirst().get("id")),"cross-JVM cursor gap");
                    check(DatabaseInstants.require(next.getFirst().get("created_at")).equals(EVENT),"microsecond drift");
                } else {
                    check(DatabaseInstants.require(mapper.rows().getFirst().get("created_at")).equals(Instant.parse("2026-11-01T00:00:00Z")),"legacy offset drift");
                }
            }
        }
        System.out.println("TIME_PROBE_PASS mode="+args[0]+" jvm="+java.time.ZoneId.systemDefault());
    }
    private static void check(boolean valid,String message) { if(!valid)throw new AssertionError(message); }
}
