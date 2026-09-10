package com.lrj.wms.probe;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 所有探针业务SQL集中在Mapper；参数绑定并以条件更新保护非负数量。 */
public interface StockProbeMapper {
    /** 影响行数为零表示资源不足，调用方不能当作成功。 */
    @Update("UPDATE stock_probe SET reserved=reserved+#{qty}, version=version+1 "
            + "WHERE warehouse_id=#{warehouse} AND sku_id=#{sku} AND on_hand-reserved>=#{qty} AND #{qty}>0")
    int reserve(@Param("warehouse") String warehouse, @Param("sku") String sku, @Param("qty") long quantity);

    /** 只按确切仓和商品读取权威数量，用于检验路由后的实际效果。 */
    @Select("SELECT reserved FROM stock_probe WHERE warehouse_id=#{warehouse} AND sku_id=#{sku}")
    long reserved(@Param("warehouse") String warehouse, @Param("sku") String sku);
}
