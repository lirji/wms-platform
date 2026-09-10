package com.lrj.wms.probe;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 履约库上的attempt/参与者/Outbox；TC审计另走只读账号。 */
public interface BusinessBarrierMapper {
    /** 绑定不可变attempt、XID、启动代际与固定参与者。 */
    @Insert("INSERT INTO allocation_attempt(enterprise_id,attempt_id,launch_epoch,xid,expected_tm,expected_group,participant_set) "
            + "VALUES(#{tenant},#{attempt},#{epoch},#{xid},#{tm},#{group},#{participants})")
    int insertAttempt(@Param("tenant") String tenant, @Param("attempt") String attempt, @Param("epoch") long epoch,
                      @Param("xid") String xid, @Param("tm") String tm, @Param("group") String group,
                      @Param("participants") String participants);

    /** 读取已持久化的启动代际；缺失表示尚未绑定。 */
    @Select("SELECT launch_epoch FROM allocation_attempt WHERE enterprise_id=#{tenant} AND attempt_id=#{attempt}")
    Long epoch(@Param("tenant") String tenant, @Param("attempt") String attempt);

    /** 读取已绑定且不可覆盖的XID。 */
    @Select("SELECT xid FROM allocation_attempt WHERE enterprise_id=#{tenant} AND attempt_id=#{attempt}")
    String xid(@Param("tenant") String tenant, @Param("attempt") String attempt);

    /** 读取该attempt允许的TM应用身份。 */
    @Select("SELECT expected_tm FROM allocation_attempt WHERE enterprise_id=#{tenant} AND attempt_id=#{attempt}")
    String expectedTm(@Param("tenant") String tenant, @Param("attempt") String attempt);

    /** 读取该attempt允许的事务分组。 */
    @Select("SELECT expected_group FROM allocation_attempt WHERE enterprise_id=#{tenant} AND attempt_id=#{attempt}")
    String expectedGroup(@Param("tenant") String tenant, @Param("attempt") String attempt);

    /** 读取逗号分隔的固定参与仓；空清单不得放行。 */
    @Select("SELECT participant_set FROM allocation_attempt WHERE enterprise_id=#{tenant} AND attempt_id=#{attempt}")
    String participants(@Param("tenant") String tenant, @Param("attempt") String attempt);

    /** 登记固定参与仓及其本地Fence观察副本。 */
    @Insert("INSERT INTO attempt_participant(enterprise_id,attempt_id,warehouse_id,fence_status) "
            + "VALUES(#{tenant},#{attempt},#{warehouse},#{status})")
    int insertParticipant(@Param("tenant") String tenant, @Param("attempt") String attempt,
                          @Param("warehouse") String warehouse, @Param("status") int status);

    /** 仅在观察到真实Fence后更新本地副本，不能写成TC决定。 */
    @Update("UPDATE attempt_participant SET fence_status=#{status} WHERE enterprise_id=#{tenant} AND attempt_id=#{attempt} AND warehouse_id=#{warehouse}")
    int updateFence(@Param("tenant") String tenant, @Param("attempt") String attempt,
                    @Param("warehouse") String warehouse, @Param("status") int status);

    /** 查询本地Fence观察副本。 */
    @Select("SELECT fence_status FROM attempt_participant WHERE enterprise_id=#{tenant} AND attempt_id=#{attempt} AND warehouse_id=#{warehouse}")
    Integer fence(@Param("tenant") String tenant, @Param("attempt") String attempt, @Param("warehouse") String warehouse);

    /** 屏障允许后写入ALLOCATED Outbox；缺证据路径不得调用。 */
    @Insert("INSERT INTO release_outbox(enterprise_id,attempt_id,payload) VALUES(#{tenant},#{attempt},'ALLOCATED')")
    int insertAllocated(@Param("tenant") String tenant, @Param("attempt") String attempt);

    /** 统计该attempt是否已写出库授权。 */
    @Select("SELECT COUNT(*) FROM release_outbox WHERE enterprise_id=#{tenant} AND attempt_id=#{attempt}")
    int outboxCount(@Param("tenant") String tenant, @Param("attempt") String attempt);
}
