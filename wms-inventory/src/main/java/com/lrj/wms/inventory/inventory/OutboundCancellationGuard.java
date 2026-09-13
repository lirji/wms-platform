package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.inventory.infrastructure.StockCommandMapper;
import org.apache.ibatis.session.SqlSession;

/** 取消释放与新STARTED使用同一原单锁；旧命令回执仍走原持久事实恢复。 */
public final class OutboundCancellationGuard {
    private OutboundCancellationGuard() {}
    public static void requireOpen(SqlSession session,String e,String w,String o) {
        com.lrj.wms.inventory.migrate.WarehouseMigrationService.requireWritable(session,e,w);
        var mapper=session.getMapper(StockCommandMapper.class);
        mapper.ensureCancellationGuard(e,w,o);
        if(mapper.lockCancellationGuard(e,w,o).get("cancellation_id")!=null)
            throw new InventoryException("CANCELLATION_IN_PROGRESS","已取消原单不能申请新的实物执行");
    }
    /** 只由可信V3业务取消调用；原TC证明必须已可靠到达，不能依赖来源自报成功。 */
    public static void stop(SqlSession session,String e,String w,String o,String allocation,String attempt,String id,String line) {
        var route=session.getMapper(com.lrj.wms.inventory.migrate.WarehouseRouteMapper.class).lock(e,w);
        if(route==null || !"ACTIVE".equals(route.get("state"))) throw new InventoryException("STALE_ROUTE","取消等待原仓路由可写");
        var mapper=session.getMapper(StockCommandMapper.class);
        mapper.ensureCancellationGuard(e,w,o);
        var guard=mapper.lockCancellationGuard(e,w,o);
        if(guard.get("cancellation_id")!=null && !id.equals(guard.get("cancellation_id")))
            throw new InventoryException("CANCELLATION_MISMATCH","原单已绑定另一取消决定");
        var tcc=session.getMapper(com.lrj.wms.inventory.tcc.RuntimeTccMapper.class);
        var intent=tcc.lock(e,w,allocation,attempt);
        var proof=intent==null?null:tcc.proof(intent.get("id").toString());
        if(proof==null || ((Number)proof.get("terminal_status")).intValue()!=9)
            throw new InventoryException("TC_TERMINAL_PENDING","等待原已提交分支的可靠终态证明");
        if(mapper.unresolvedOutboundPermits(e,w,o,line)>0)
            throw new InventoryException("PHYSICAL_RESULT_UNKNOWN","原STARTED或UNKNOWN尚未结案，不能释放库存");
        if(guard.get("cancellation_id")==null && mapper.stopCancellationGuard(e,w,o,id)!=1)
            throw new InventoryException("VERSION_CONFLICT","取消门禁竞争");
    }
}
