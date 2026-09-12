package com.lrj.wms.inbound.receipt;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 分批质量只查询本库来源事实，锁定数量边界后才发布库存命令。 */
public interface ReceiptQualityMapper {
    Map<String, Object> receipt(@Param("ent") String ent, @Param("wh") String wh, @Param("receipt") String receipt);
    int initialize(@Param("id") String id, @Param("ent") String ent, @Param("wh") String wh,
            @Param("receipt") String receipt, @Param("line") String line, @Param("now") Timestamp now);
    Map<String, Object> lock(@Param("ent") String ent, @Param("wh") String wh, @Param("receipt") String receipt);
    List<Map<String, Object>> revisions(@Param("ent") String ent, @Param("wh") String wh,
            @Param("receipt") String receipt, @Param("inspection") String inspection,
            @Param("command") String command, @Param("sourceVersion") long sourceVersion);
    int insertRevision(@Param("ent") String ent, @Param("wh") String wh, @Param("receipt") String receipt,
            @Param("inspection") String inspection, @Param("command") String command, @Param("sourceVersion") long sourceVersion,
            @Param("hash") String hash, @Param("actor") String actor, @Param("accepted") BigDecimal accepted,
            @Param("rejected") BigDecimal rejected, @Param("now") Timestamp now);
    int accept(@Param("ent") String ent, @Param("wh") String wh, @Param("receipt") String receipt,
            @Param("command") String command, @Param("sourceVersion") long sourceVersion, @Param("expected") long expected,
            @Param("accepted") BigDecimal accepted, @Param("rejected") BigDecimal rejected, @Param("now") Timestamp now);
    int applied(@Param("ent") String ent, @Param("wh") String wh, @Param("command") String command,
            @Param("state") String state, @Param("now") Timestamp now);
}
