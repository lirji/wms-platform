package com.lrj.wms.fulfillment;

import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 只读TC审计库；此Mapper没有任何二阶段或写入SQL。 */
public interface TcEvidenceMapper {
    /** 单XID主键查询且限制语句耗时，Finished或global_table缺行都不能生成证据。 */
    Map<String, Object> find(@Param("xid") String xid);
    /** 健康检查验证同一张审计表的SELECT权限，不暴露任何事务正文。 */
    int available();
}
