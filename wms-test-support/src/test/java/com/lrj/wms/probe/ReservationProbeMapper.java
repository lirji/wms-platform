package com.lrj.wms.probe;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 预占业务键与所有者绑定；冲突必须显式失败，不能改绑已有资源。 */
public interface ReservationProbeMapper {
    /** 首次Try写入所有者；唯一键冲突表示该业务键已被占用。 */
    @Insert("INSERT INTO reservation_probe(enterprise_id,warehouse_id,allocation_id,attempt_id,xid,branch_id,action_name,request_digest,reserved_qty) "
            + "VALUES(#{tenant},#{warehouse},#{allocation},#{attempt},#{xid},#{branch},#{action},#{digest},#{qty})")
    int insert(@Param("tenant") String tenant, @Param("warehouse") String warehouse, @Param("allocation") String allocation,
               @Param("attempt") String attempt, @Param("xid") String xid, @Param("branch") long branch,
               @Param("action") String action, @Param("digest") String digest, @Param("qty") long quantity);

    @Select("SELECT xid FROM reservation_probe WHERE enterprise_id=#{tenant} AND warehouse_id=#{warehouse} "
            + "AND allocation_id=#{allocation} AND attempt_id=#{attempt}")
    String ownerXid(@Param("tenant") String tenant, @Param("warehouse") String warehouse,
                    @Param("allocation") String allocation, @Param("attempt") String attempt);

    @Select("SELECT branch_id FROM reservation_probe WHERE enterprise_id=#{tenant} AND warehouse_id=#{warehouse} "
            + "AND allocation_id=#{allocation} AND attempt_id=#{attempt}")
    Long ownerBranch(@Param("tenant") String tenant, @Param("warehouse") String warehouse,
                     @Param("allocation") String allocation, @Param("attempt") String attempt);

    @Select("SELECT request_digest FROM reservation_probe WHERE enterprise_id=#{tenant} AND warehouse_id=#{warehouse} "
            + "AND allocation_id=#{allocation} AND attempt_id=#{attempt}")
    String digest(@Param("tenant") String tenant, @Param("warehouse") String warehouse,
                  @Param("allocation") String allocation, @Param("attempt") String attempt);

    /** 只有完全匹配的所有者才能释放；非所有者影响行数为0，调用方不得改库存。 */
    @Update("DELETE FROM reservation_probe WHERE enterprise_id=#{tenant} AND warehouse_id=#{warehouse} "
            + "AND allocation_id=#{allocation} AND attempt_id=#{attempt} "
            + "AND xid=#{xid} AND branch_id=#{branch} AND action_name=#{action}")
    int deleteOwner(@Param("tenant") String tenant, @Param("warehouse") String warehouse, @Param("allocation") String allocation,
                    @Param("attempt") String attempt, @Param("xid") String xid, @Param("branch") long branch, @Param("action") String action);
}
