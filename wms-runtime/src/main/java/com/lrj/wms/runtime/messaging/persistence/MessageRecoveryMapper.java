package com.lrj.wms.runtime.messaging.persistence;

import com.lrj.wms.runtime.web.CursorPage;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 恢复只操作当前物理库；企业/仓条件不能被运维权限省略。 */
public interface MessageRecoveryMapper {
    /** 返回有界元数据页，不公开消息正文或其他租户的隔离记录。 */
    List<Map<String, Object>> list(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("queue") String queue, @Param("status") String status, @Param("page") CursorPage page);

    /** 锁住原消息，使状态切换与审计在单事务内完成。 */
    Map<String, Object> lockMessage(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("queue") String queue, @Param("messageId") String messageId);

    /** 同企业/仓的命令键唯一，重放不能再次恢复预算。 */
    Map<String, Object> audit(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId);

    /** 仅隔离且代际仍匹配的记录可恢复，绝不回退claim_epoch。 */
    int retry(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("queue") String queue, @Param("messageId") String messageId, @Param("epoch") long epoch, @Param("now") Timestamp now);

    /** 重试记录和恢复预算同事务；拒绝只改状态不留审计。 */
    int insertAudit(@Param("row") Map<String, Object> row);
}
