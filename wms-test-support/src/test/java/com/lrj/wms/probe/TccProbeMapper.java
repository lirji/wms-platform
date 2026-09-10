package com.lrj.wms.probe;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 二阶段探针SQL，必须使用Fence绑定的Spring事务连接。 */
public interface TccProbeMapper {
    /** 持久化回调效果，唯一键使意外重复业务执行显式失败。 */
    @Insert("INSERT INTO callback_effect(xid,branch_id,phase) VALUES(#{xid},#{branch},#{phase})")
    int effect(@Param("xid") String xid, @Param("branch") long branch, @Param("phase") String phase);

    /** 仅释放本探针已Try成功的数量；正式业务还需要reservation所有权校验。 */
    @Update("UPDATE stock_probe SET reserved=reserved-#{qty},version=version+1 "
            + "WHERE warehouse_id=#{warehouse} AND sku_id='sku' AND reserved>=#{qty}")
    int release(@Param("warehouse") String warehouse, @Param("qty") long quantity);
}
