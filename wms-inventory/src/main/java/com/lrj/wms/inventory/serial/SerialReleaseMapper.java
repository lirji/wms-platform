package com.lrj.wms.inventory.serial;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 源仓释放的独立持久恢复队列，所有读写固定原企业仓。 */
public interface SerialReleaseMapper {
    /** 仅用于决定先锁哪个门禁；锁后必须再次验证本地身份仍在该桶。 */
    Map<String,Object> location(@Param("e") String e,@Param("w") String w,@Param("serial") String serial);
    int insert(@Param("row") Map<String,Object> row);
    Map<String,Object> lock(@Param("e") String e,@Param("w") String w,@Param("id") String id);
    Map<String,Object> next(@Param("e") String e,@Param("w") String w,@Param("now") Timestamp now);
    int claim(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("epoch") long epoch,@Param("until") Timestamp until,@Param("now") Timestamp now);
    int finish(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("epoch") long epoch,@Param("state") String state,@Param("error") String error,@Param("next") Timestamp next,@Param("now") Timestamp now);
    /** 人工核查后仅重新授予预算，原业务事实不可变。 */
    int requeue(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("epoch") long epoch,@Param("now") Timestamp now);
}
