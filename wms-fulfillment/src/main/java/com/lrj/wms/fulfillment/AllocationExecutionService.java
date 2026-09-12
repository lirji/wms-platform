package com.lrj.wms.fulfillment;

import com.lrj.wms.contract.tcc.WarehouseTryRequest;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

/** 固定计划的执行命令；只持久化调度输入，不在HTTP事务中调用TC或仓服务。 */
public final class AllocationExecutionService {
    private final SqlSessionFactory sessions;
    private final TcEvidenceScope scope;
    private final Clock clock;
    public AllocationExecutionService(SqlSessionFactory sessions,TcEvidenceScope scope,Clock clock) {
        this.sessions=sessions;this.scope=scope;this.clock=clock;
    }

    /** 每个attempt只接受一个不可变执行请求；核对全部仓、原订单行、货主和效期条件。 */
    public Map<String,Object> submit(String enterprise,String fulfillment,String attempt,String command,String actor,
            List<WarehouseTryRequest> requests) {
        for(String id:List.of(enterprise,fulfillment,attempt,command,actor))
            if(id.isBlank()||id.length()>64) throw error("INVALID_ARGUMENT");
        if(requests==null||requests.isEmpty()||requests.size()>200) throw error("INVALID_PARTICIPANT");
        var fixed=requests.stream().sorted(Comparator.comparing(WarehouseTryRequest::warehouseId)).toList();
        Set<String> warehouses=new HashSet<>();
        for(var request:fixed) if(!enterprise.equals(request.enterpriseId()) || !attempt.equals(request.attemptId())
                || !attempt.equals(request.allocationId()) || !warehouses.add(request.warehouseId())) throw error("EXECUTION_SCOPE_MISMATCH");
        String payload=RuntimeMessage.JSON.writeValueAsString(fixed);
        if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60000) throw error("EXECUTION_PLAN_TOO_LARGE");
        String digest=RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(List.of("execution-v1",fulfillment,payload,scope)));
        try(var session=sessions.openSession(false)) {
            var executions=session.getMapper(AllocationExecutionMapper.class);
            var row=new LinkedHashMap<String,Object>();
            row.put("enterprise_id",enterprise);row.put("attempt_id",attempt);row.put("fulfillment_id",fulfillment);
            row.put("command_id",command);row.put("actor_id",actor);row.put("payload_hash",digest);row.put("try_payload",payload);
            row.put("cluster_id",scope.clusterId());row.put("transaction_group",scope.transactionGroup());
            row.put("launch_owner",UUID.randomUUID().toString());row.put("now",Timestamp.from(clock.instant()));
            executions.insert(row);
            var existing=executions.lock(enterprise,attempt);
            if(existing==null||!command.equals(existing.get("command_id"))||!digest.equals(existing.get("payload_hash")))
                throw error("IDEMPOTENCY_PAYLOAD_MISMATCH");
            // 已受理命令只回原结果；不会用新的活动attempt、货主或配置重算执行输入。
            if(row.get("launch_owner").equals(existing.get("launch_owner"))) validate(session,enterprise,fulfillment,attempt,fixed);
            var result=status(existing);session.commit();return result;
        }
    }

    private void validate(SqlSession session,String e,String fulfillment,String attempt,List<WarehouseTryRequest> requests) {
        var mapper=session.getMapper(FulfillmentMapper.class);
        var order=mapper.lockOrder(e,fulfillment);var row=mapper.lockAttempt(e,attempt);
        if(order==null||row==null||!fulfillment.equals(row.get("fulfillment_id"))||!attempt.equals(order.get("active_attempt_id")))
            throw error("ACTIVE_ATTEMPT_MISMATCH");
        if(order.get("owner_id")==null) throw error("OWNER_REQUIRED");
        if(!"PLANNED".equals(row.get("state"))||row.get("xid")!=null||row.get("launch_owner")!=null) throw error("ATTEMPT_ALREADY_STARTED");
        if(cancelled(row)||!instant(row.get("deadline")).isAfter(clock.instant().plusSeconds(1))) throw error("ATTEMPT_NOT_EXECUTABLE");
        var expected=new TreeMap<String,Map<String,Object>>();
        for(var line:mapper.listParticipantLines(e,attempt)) expected.put(key(line.get("warehouse_id"),line.get("order_line_id")),line);
        Map<String,Integer> days=new HashMap<>();
        for(var line:mapper.lockLines(e,fulfillment)) days.put(line.get("source_line_id").toString(),((Number)line.get("min_remaining_days")).intValue());
        var totals=new HashMap<String,BigDecimal>();
        for(var request:requests) {
            if(!order.get("owner_id").equals(request.ownerId())) throw error("OWNER_MISMATCH");
            for(var line:request.lines()) {
                String key=key(request.warehouseId(),line.orderLineId());var original=expected.get(key);
                if(original==null||!original.get("sku_id").equals(line.skuId())||!original.get("base_unit").equals(line.baseUnit())
                        ||!Objects.equals(days.get(line.orderLineId()),line.minRemainingDays())) throw error("EXECUTION_LINE_MISMATCH");
                totals.merge(key,line.qty(),BigDecimal::add);
            }
        }
        if(!totals.keySet().equals(expected.keySet())) throw error("EXECUTION_LINE_MISMATCH");
        for(var entry:expected.entrySet()) if(totals.get(entry.getKey()).compareTo(new BigDecimal(entry.getValue().get("qty").toString()))!=0)
            throw error("EXECUTION_QUANTITY_MISMATCH");
    }

    /** 只返回用户判断进度所需的信息，凭据和底层请求正文不进入工作台。 */
    public static Map<String,Object> status(Map<String,Object> row) {
        if(row==null) return Map.of();
        var result=new LinkedHashMap<String,Object>();
        result.put("state",row.get("state"));result.put("xid",row.get("xid"));result.put("requestedAction",row.get("requested_action"));
        result.put("completedWarehouses",row.get("next_warehouse"));result.put("retryCount",row.get("rpc_attempts"));
        result.put("errorCode",row.get("error_code"));result.put("updatedAt",row.get("updated_at"));return result;
    }
    static List<WarehouseTryRequest> requests(Map<String,Object> row) {
        return List.of(RuntimeMessage.JSON.readValue(row.get("try_payload").toString(),WarehouseTryRequest[].class));
    }
    static Instant instant(Object value) {
        if(value instanceof Timestamp stamp) return stamp.toInstant();
        if(value instanceof LocalDateTime local) return com.lrj.wms.runtime.db.DatabaseInstants.require(local);
        throw error("EXECUTION_TIME_INVALID");
    }
    static boolean cancelled(Map<String,Object> row) {
        Object value=row.get("cancel_requested");return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()!=0;
    }
    private static String key(Object warehouse,Object line) {return RuntimeMessage.JSON.writeValueAsString(List.of(warehouse,line));}
    static FulfillmentException error(String code) {return new FulfillmentException(code,"分配执行条件不满足，保留原命令与恢复记录");}
}
