package com.lrj.wms.inventory.serial;

import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 拣货事实绑定原预占与具体身份，禁止以数量桶猜测归属。 */
public interface SerialOutboundMapper {
    /** 唯一冲突或最后写失败均交给用例回滚数量和身份，不吞数据库完整性错误。 */
    int insert(@Param("row") Map<String,Object> row);
    /** 只读完整桶下的当前可选身份，不把查询结果当作已占用凭证。 */
    java.util.List<Map<String,Object>> available(@Param("e") String e,@Param("w") String w,@Param("owner") String owner,
            @Param("sku") String sku,@Param("location") String location,@Param("lot") String lot,@Param("page") com.lrj.wms.runtime.web.CursorPage page);
}
