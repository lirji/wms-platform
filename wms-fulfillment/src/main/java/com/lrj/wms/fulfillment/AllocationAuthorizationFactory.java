package com.lrj.wms.fulfillment;

import com.lrj.wms.contract.messaging.AllocationAuthorization;
import com.lrj.wms.runtime.messaging.AllocationAuthorizationMessage;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;

/** 成功屏障事务内构造授权，不向旧未知货主或缺少原TC来源的记录补猜运行权限。 */
final class AllocationAuthorizationFactory {
    private AllocationAuthorizationFactory() { }

    static Map<String, String> snapshots(SqlSession session, String enterprise, Map<String,Object> order,
            Map<String,Object> attempt, List<Map<String,Object>> participants, List<Map<String,Object>> lines) {
        if (order.get("owner_id") == null) return Map.of();
        String attemptId = text(attempt,"id"), xid = text(attempt,"xid");
        var binding = session.getMapper(AllocationRecoveryMapper.class).binding(enterprise,attemptId);
        if (binding == null || !xid.equals(binding.get("xid"))
                || number(attempt,"launch_epoch") != number(binding,"launch_epoch"))
            throw new FulfillmentException("TC_BINDING_MISSING", "缺少本attempt原始TC绑定，不能自动授权");
        var proof = new AllocationAuthorization.TcProof(text(binding,"cluster_id"),text(binding,"application_id"),
                text(binding,"transaction_group"),text(attempt,"tc_terminal_evidence"));
        var confirmations = participants.stream().map(row -> new AllocationAuthorization.Participant(
                text(row,"warehouse_id"),text(row,"confirmed_allocation_id"),text(row,"reservation_id"),
                number(row,"branch_id"),text(row,"action_name"),number(row,"route_epoch"),number(row,"confirmed_version"))).toList();
        Map<String,String> result = new LinkedHashMap<>();
        for (var participant : confirmations) {
            String warehouse=participant.warehouseId(), allocation=participant.allocationId();
            var ownedLines=lines.stream().filter(row -> warehouse.equals(row.get("warehouse_id")))
                    .map(row -> new AllocationAuthorization.Line(text(row,"order_line_id"),text(row,"sku_id"),
                            new BigDecimal(text(row,"qty")),text(row,"base_unit")))
                    .sorted(java.util.Comparator.comparing(AllocationAuthorization.Line::orderLineId)).toList();
            var body=new AllocationAuthorization(1,enterprise,warehouse,text(order,"id"),text(order,"owner_id"),
                    allocation,attemptId,xid,AllocationAuthorization.authorizationKey(enterprise,warehouse,attemptId,allocation),
                    AllocationAuthorization.evidenceReference(proof,xid),text(attempt,"participant_set_hash"),proof,confirmations,ownedLines);
            var json=RuntimeMessage.JSON.valueToTree(body);
            AllocationAuthorizationMessage.parse(json);
            result.put(warehouse,json.toString());
        }
        return result;
    }
    private static String text(Map<String,Object> row,String field) {
        Object value=row.get(field);
        if (value == null || value.toString().isBlank()) throw new FulfillmentException("AUTHORIZATION_FACT_MISSING","成功屏障缺少原始事实");
        return value.toString();
    }
    private static long number(Map<String,Object> row,String field) {
        Object value=row.get(field);
        if (!(value instanceof Number number)) throw new FulfillmentException("AUTHORIZATION_FACT_MISSING","成功屏障缺少原始版本");
        return number.longValue();
    }
}
