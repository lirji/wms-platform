package com.lrj.wms.inventory.tcc;

import com.lrj.wms.contract.messaging.TcTerminalNotice;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.CommandDigest;
import com.lrj.wms.inventory.migrate.WarehouseRouteMapper;
import com.lrj.wms.runtime.messaging.*;
import java.util.*;
import org.apache.ibatis.session.SqlSession;

/** 可信TC通知只追加证据；Fence及库存业务状态必须已经由真正TC回调完成。 */
public final class TcTerminalService {
    private TcTerminalService() {}
    public static void accept(SqlSession session,RuntimeMessage message,String cluster,String group) {
        if(!"wms-fulfillment".equals(message.sourceService()) || !TcTerminalNotice.EVENT.equals(message.eventType())) throw new MessageRejectedException("INVALID_TC_NOTICE");
        TcTerminalNotice notice;
        try {notice=RuntimeMessage.JSON.treeToValue(message.payload(),TcTerminalNotice.class);}
        catch(RuntimeException invalid) {throw new MessageRejectedException("INVALID_TC_NOTICE");}
        if(!Objects.equals(cluster,notice.clusterId()) || !Objects.equals(group,notice.transactionGroup())
                || !message.aggregateId().equals(notice.attemptId())) throw new MessageRejectedException("TC_NOTICE_SCOPE_MISMATCH");
        String e=message.enterpriseId(),w=message.warehouseId();
        var route=session.getMapper(WarehouseRouteMapper.class).lock(e,w);
        if(route==null || !"ACTIVE".equals(route.get("state"))) throw new InventoryException("STALE_ROUTE","终态证明等待可写的原仓路由");
        var mapper=session.getMapper(RuntimeTccMapper.class);
        var intent=mapper.lock(e,w,notice.allocationId(),notice.attemptId());
        if(intent==null || intent.get("branch_id")==null) throw new InventoryException("TC_BRANCH_PENDING","原分支尚不可证明，保留通知恢复");
        if(!notice.xid().equals(intent.get("xid")) || !resource(cluster,(String)intent.get("cell_id")).equals(intent.get("action_name")))
            throw new MessageRejectedException("TC_NOTICE_IDENTITY_MISMATCH");
        var fence=mapper.fence(notice.xid(),((Number)intent.get("branch_id")).longValue());
        int status=notice.terminalStatus().intValue();
        if(!matches(intent,fence,status)) throw new InventoryException("TC_LOCAL_TERMINAL_PENDING","真实TC回调/Fence尚未与全局终态一致");
        String json=RuntimeMessage.JSON.writeValueAsString(notice),hash=RuntimeMessage.contentHash(json);
        mapper.terminal(intent,status,json,hash);
        var proof=mapper.proof((String)intent.get("id"));
        if(proof==null || !hash.equals(proof.get("proof_hash"))) throw new MessageRejectedException("TC_TERMINAL_CONFLICT");
    }
    /** 精确保留官方Fence状态；空回滚为4，已执行回滚为3。 */
    static boolean matches(Map<String,Object> intent,Map<String,Object> fence,int status) {
        if(fence==null || !intent.get("action_name").equals(fence.get("action_name"))) return false;
        int phase=((Number)fence.get("status")).intValue();
        return status==9?"CONFIRMED".equals(intent.get("state")) && phase==2:
                (status==11 || status==13) && "CANCELLED".equals(intent.get("state")) && (phase==3 || phase==4);
    }
    /** 与RM现有资源算法一致，不接受消息任意指定资源。 */
    public static String resource(String cluster,String cell) {
        return "WmsReserveV1-"+Base64.getUrlEncoder().withoutPadding().encodeToString(HexFormat.of().parseHex(CommandDigest.v1Parts(cluster,cell)));
    }
}
