package com.lrj.wms.fulfillment;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 持久化扫描检查点及TC绑定来源，避免前100个悬挂事务永久饿死后续attempt。 */
public interface AllocationRecoveryMapper {
    /** 绑定与attempt.xid必须在同一本地事务提交，禁止对历史行按当前配置猜测回填。 */
    int bind(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId,
            @Param("xid") String xid, @Param("epoch") long epoch, @Param("scope") TcEvidenceScope scope,
            @Param("now") Timestamp now);
    Map<String, Object> binding(@Param("enterpriseId") String enterpriseId, @Param("attemptId") String attemptId);
    int ensureCursor(@Param("enterpriseId") String enterpriseId, @Param("now") Timestamp now);
    Map<String, Object> cursor(@Param("enterpriseId") String enterpriseId);
    /** 按企业限20项，网络调用前关闭会话；仅在途及缺失屏障事件的已分配项可见。 */
    List<Map<String, Object>> page(@Param("enterpriseId") String enterpriseId, @Param("afterId") String afterId);
    /** 只有读到的版本可推进，不让并发扫描回退已提交检查点。 */
    int advance(@Param("enterpriseId") String enterpriseId, @Param("version") long version,
            @Param("afterId") String afterId, @Param("now") Timestamp now);
}
