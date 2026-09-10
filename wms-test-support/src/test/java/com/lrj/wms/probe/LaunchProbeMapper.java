package com.lrj.wms.probe;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 启动协议SQL集中于持久化层；TC网络调用不能在这些本地事务内执行。 */
public interface LaunchProbeMapper {
    /** 只能由空活动槽选择尝试；未知或已绑定尝试不能直接替换。 */
    @Update("UPDATE allocation_slot SET active_attempt_id=#{attempt} WHERE enterprise_id=#{tenant} AND allocation_id=#{allocation} AND active_attempt_id IS NULL")
    int activate(@Param("tenant") String tenant, @Param("allocation") String allocation, @Param("attempt") String attempt);
    /** 和活动槽CAS同事务插入；失败需把活动槽一起回滚。 */
    @Insert("INSERT INTO launch_attempt(enterprise_id,attempt_id,allocation_id,state,entry_protocol_version) VALUES(#{tenant},#{attempt},#{allocation},'READY',#{proof})")
    int insert(@Param("tenant") String tenant, @Param("allocation") String allocation, @Param("attempt") String attempt, @Param("proof") int proof);
    /** 数据库时钟决定租约，并核对活动尝试；返回0不得begin。 */
    @Update("UPDATE launch_attempt a JOIN allocation_slot s ON s.enterprise_id=a.enterprise_id AND s.allocation_id=a.allocation_id "
            + "SET a.state='LAUNCHING',a.launch_owner=#{owner},a.lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 60 SECOND),a.version=a.version+1 "
            + "WHERE a.enterprise_id=#{tenant} AND a.attempt_id=#{attempt} AND s.active_attempt_id=a.attempt_id AND a.state='READY' AND a.xid IS NULL AND a.launch_epoch=#{epoch} AND a.version=#{version}")
    int claim(@Param("tenant") String tenant, @Param("attempt") String attempt, @Param("owner") String owner, @Param("epoch") long epoch, @Param("version") long version);
    /** 绑定必须仍持有有效代际/租约，不存在覆盖已绑定XID的SQL。 */
    @Update("UPDATE launch_attempt SET xid=#{xid},state='TCC_TRYING',version=version+1 WHERE enterprise_id=#{tenant} AND attempt_id=#{attempt} "
            + "AND state='LAUNCHING' AND xid IS NULL AND launch_owner=#{owner} AND launch_epoch=#{epoch} AND version=#{version} AND lease_until>CURRENT_TIMESTAMP(6)")
    int bind(@Param("tenant") String tenant, @Param("attempt") String attempt, @Param("owner") String owner, @Param("epoch") long epoch, @Param("version") long version, @Param("xid") String xid);
    /** 仅无绑定、入口已受控且租约过期的启动能提升代际；租约本身不足以证明可重开。 */
    @Update("UPDATE launch_attempt SET state='READY',launch_epoch=launch_epoch+1,version=version+1,launch_owner=NULL,lease_until=NULL "
            + "WHERE enterprise_id=#{tenant} AND attempt_id=#{attempt} AND state='LAUNCHING' AND xid IS NULL AND launch_epoch=#{epoch} AND version=#{version} "
            + "AND lease_until<=CURRENT_TIMESTAMP(6) AND entry_protocol_version=1")
    int fenceUnbound(@Param("tenant") String tenant, @Param("attempt") String attempt, @Param("epoch") long epoch, @Param("version") long version);
    /** 绑定结果未知时用权威读恢复；真实实现不得读取延迟副本或缓存。 */
    @Select("SELECT xid FROM launch_attempt WHERE enterprise_id=#{tenant} AND attempt_id=#{attempt}")
    String boundXid(@Param("tenant") String tenant, @Param("attempt") String attempt);
}
