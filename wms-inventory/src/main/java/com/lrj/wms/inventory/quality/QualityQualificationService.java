package com.lrj.wms.inventory.quality;

import com.lrj.wms.inventory.inventory.InventoryException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 按质检版本接收质量资格。乱序旧版本保持已生效结论。不写入库单。 */
public final class QualityQualificationService {
    public static final String STATE_EFFECTIVE = "EFFECTIVE";

    private final SqlSession session;
    private final Clock clock;

    public QualityQualificationService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** 首次写入或仅当 sourceVersion 更新时覆盖。 */
    public Map<String, Object> apply(String enterpriseId, String warehouseId, String inspectionId, long sourceVersion,
            String skuId, String lotId, String resultCode, String commandId) {
        Timestamp now = Timestamp.from(clock.instant());
        QualityQualificationMapper mapper = session.getMapper(QualityQualificationMapper.class);
        Map<String, Object> existing = mapper.lockByInspection(enterpriseId, warehouseId, inspectionId);
        if (existing == null) {
            mapper.insert(UUID.randomUUID().toString(), enterpriseId, warehouseId, inspectionId, sourceVersion, skuId,
                    lotId, resultCode, STATE_EFFECTIVE, commandId, now);
            return view(mapper.lockByInspection(enterpriseId, warehouseId, inspectionId));
        }
        long current = ((Number) existing.get("source_version")).longValue();
        if (sourceVersion <= current) {
            return view(existing);
        }
        if (mapper.casNewerVersion(enterpriseId, warehouseId, inspectionId, sourceVersion, resultCode, STATE_EFFECTIVE,
                commandId, now) != 1) {
            throw new InventoryException("VERSION_CONFLICT", "质量资格版本冲突");
        }
        return view(mapper.lockByInspection(enterpriseId, warehouseId, inspectionId));
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inspectionId", row.get("inspection_id"));
        body.put("sourceVersion", row.get("source_version"));
        body.put("resultCode", row.get("result_code"));
        body.put("effectiveState", row.get("effective_state"));
        body.put("commandId", row.get("command_id"));
        return body;
    }
}
