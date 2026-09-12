package com.lrj.wms.runtime;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
/** 真库跨JVM时间探针，使用和业务Map查询相同的映射路径。 */
public interface TimeProbeMapper {
    int insert(@Param("id") String id,@Param("time") Timestamp time);
    List<Map<String,Object>> rows();
    List<Map<String,Object>> after(@Param("page") com.lrj.wms.runtime.web.CursorPage page);
}
