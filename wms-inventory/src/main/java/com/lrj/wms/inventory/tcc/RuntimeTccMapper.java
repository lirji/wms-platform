package com.lrj.wms.inventory.tcc;

import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** RM持久化边界；登记意图先提交，网络在事务外，所有阶段按原身份条件更新。 */
public interface RuntimeTccMapper {
    void insert(@Param("id") String id,@Param("r") com.lrj.wms.contract.tcc.WarehouseTryRequest request,
            @Param("xid") String xid,@Param("action") String action,@Param("digest") String digest,@Param("payload") String payload);
    Map<String,Object> lock(@Param("enterprise") String enterprise,@Param("warehouse") String warehouse,
            @Param("allocation") String allocation,@Param("attempt") String attempt);
    int bindBranch(@Param("id") String id,@Param("branch") long branch);
    int tried(@Param("id") String id,@Param("branch") long branch,@Param("reservation") String reservation);
    int finished(@Param("id") String id,@Param("branch") long branch,@Param("state") String state);
}
