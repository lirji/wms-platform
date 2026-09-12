package com.lrj.wms.contract.tcc;

import java.math.BigDecimal;
import java.util.List;

/** V1仓级Try冻结事实；调用方必须提供确切库存桶，RM不猜货主或临时改仓。 */
public record WarehouseTryRequest(int schemaVersion,String enterpriseId,String warehouseId,String ownerId,
        String allocationId,String attemptId,String cellId,long routeEpoch,List<Line> lines) {
    public WarehouseTryRequest {
        if(schemaVersion!=1 || routeEpoch<1) throw new IllegalArgumentException("Try版本或路由代际无效");
        for(String id:List.of(enterpriseId,warehouseId,ownerId,allocationId,attemptId,cellId)) requireId(id);
        if(lines==null || lines.isEmpty() || lines.size()>200) throw new IllegalArgumentException("Try行数必须为1至200");
        lines=lines.stream().sorted(java.util.Comparator.comparing(Line::orderLineId).thenComparing(Line::sourceLocationId)
                .thenComparing(Line::lotId)).toList();
        var seen=new java.util.HashSet<List<String>>();
        for(var line:lines) if(!seen.add(List.of(line.orderLineId(),line.sourceLocationId(),line.lotId())))
            throw new IllegalArgumentException("Try原订单行与桶重复");
    }
    /** 明确基本单位、数量和最小剩余效期；一个订单行可由多个固定桶满足。 */
    public record Line(String orderLineId,String skuId,String sourceLocationId,String lotId,BigDecimal qty,
            String baseUnit,int minRemainingDays) {
        public Line {
            for(String id:List.of(orderLineId,skuId,sourceLocationId,lotId,baseUnit)) requireId(id);
            if(qty==null || qty.signum()<=0 || qty.stripTrailingZeros().scale()>6
                    || qty.abs().compareTo(new BigDecimal("100000000000000"))>=0 || minRemainingDays<0)
                throw new IllegalArgumentException("Try数量或效期无效");
            qty=qty.stripTrailingZeros();
        }
    }
    private static void requireId(String id) {
        if(id==null || id.isBlank() || id.length()>64) throw new IllegalArgumentException("Try标识不能为空且最长64字符");
    }
}
