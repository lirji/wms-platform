package com.lrj.wms.inventory.tcc;

import com.lrj.wms.contract.tcc.WarehouseTryRequest;
import com.lrj.wms.contract.tcc.WarehouseTryResult;
import com.lrj.wms.inventory.inventory.*;
import com.lrj.wms.inventory.inventory.domain.*;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataHttpMapper;
import com.lrj.wms.inventory.migrate.WarehouseRouteMapper;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.*;
import org.apache.seata.common.Constants;
import org.apache.seata.common.executor.Callback;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.integration.tx.api.fence.FenceHandler;
import org.apache.seata.rm.fence.SpringFenceHandler;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextUtil;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 正式RM用例。登记意图、网络、Fence事务分开；只有TC资源回调进入二阶段。 */
public final class RuntimeTccCoordinator implements FenceHandler {
    @FunctionalInterface public interface Registration {long register(String xid,String applicationData);}
    private final SqlSessionTemplate sessions;
    private final TransactionTemplate transactions;
    private final SpringFenceHandler fence;
    private final Clock clock;
    private final String cellId,actionName;
    private final com.lrj.wms.runtime.web.AdmissionGate admission=new com.lrj.wms.runtime.web.AdmissionGate(
            new com.lrj.wms.runtime.web.AdmissionBudget(4,2,32,16));

    public RuntimeTccCoordinator(SqlSessionTemplate sessions,TransactionTemplate transactions,SpringFenceHandler fence,
            Clock clock,String cellId,String actionName) {
        this.sessions=sessions;
        // 外层事务为官方Fence和业务统一设置10秒预算，不随200行逐语句累加到无界时长。
        this.transactions=new TransactionTemplate(java.util.Objects.requireNonNull(transactions.getTransactionManager()));
        this.transactions.setTimeout(10);
        this.fence=fence;this.clock=clock;
        this.cellId=cellId;this.actionName=actionName;
    }
    public String actionName(){return actionName;}
    /** 只注册本物理cell已经激活的历史资源；每进程最多256个，超限明确拒绝而非默默漏回调。 */
    public List<String> historicalResources() {
        var resources=mapper().historicalResources(cellId);
        if(resources.size()>256) throw failure("TCC_RESOURCE_CAPACITY_EXCEEDED");
        return resources;
    }

    /** HTTP重放沿用原branch；REGISTERING表示结果未知，永远不据租约再次登记。 */
    public WarehouseTryResult tryReserve(WarehouseTryRequest request,String xid,Registration registration) {
        if(xid==null || xid.isBlank() || xid.length()>128) throw failure("XID_REQUIRED");
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw failure("TCC_NETWORK_IN_TRANSACTION_FORBIDDEN");
        if(RootContext.getXID()!=null || BusinessActionContextUtil.getContext()!=null) throw failure("TCC_CONTEXT_ALREADY_BOUND");
        var permit=admission.acquire(request.enterpriseId());
        if(permit==null) throw failure("TCC_ADMISSION_REJECTED");
        try(permit) {
            String payload=RuntimeMessage.JSON.writeValueAsString(request);
            if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60000) throw failure("TCC_REQUEST_TOO_LARGE");
            String digest=CommandDigest.v1Parts("runtime-try-v1",payload),id=UUID.randomUUID().toString();
            Map<String,Object> intent=transactions.execute(status->{
                requireRoute(request.enterpriseId(),request.warehouseId(),request.cellId(),request.routeEpoch());
                mapper().insert(id,request,xid,actionName,digest,payload);
                var row=lock(request);
                if(!xid.equals(row.get("xid")) || !digest.equals(row.get("request_digest")) || !actionName.equals(row.get("action_name")))
                    throw failure("TCC_OWNER_CONFLICT");
                return row;
            });
            if("CANCELLED".equals(intent.get("state"))) throw failure("TCC_ALREADY_CANCELLED");
            if(intent.get("reservation_id")!=null) return result(intent);
            String data=applicationData(intent);
            if(id.equals(intent.get("id"))) {
                // 意图已提交；如果进程在此调用前后消失，重试只能观察原XID，不能再登记。
                long branch=registration.register(xid,data);
                if(branch<=0) throw failure("TCC_REGISTRATION_UNKNOWN");
                intent=transactions.execute(status->{
                    requireRoute(request.enterpriseId(),request.warehouseId(),request.cellId(),request.routeEpoch());
                    var row=lock(request);
                    bindBranch(row,branch);
                    return lock(request);
                });
            }
            if(intent.get("branch_id")==null) throw failure("TCC_REGISTRATION_UNKNOWN");
            if("CANCELLED".equals(intent.get("state"))) throw failure("TCC_ALREADY_CANCELLED");
            long branch=((Number)intent.get("branch_id")).longValue();
            var context=BusinessActionContextUtil.getBusinessActionContext(xid,branch,actionName,data);
            BusinessActionContextUtil.setContext(context);
            try {
                Object prepared=prepareFence(xid,branch,actionName,()->{
                    var row=guard(context,xid,branch);
                    if(!"REGISTERED".equals(row.get("state"))) throw failure("TCC_TRY_STATE_CONFLICT");
                    var lines=reservationLines(request);
                    String reservation=inventory().reserveTried(request.enterpriseId(),request.warehouseId(),
                            operation("try",context),request.allocationId(),actionName,request.allocationId(),request.attemptId(),
                            xid,branch,actionName,request.routeEpoch(),digest,lines);
                    if(mapper().tried(String.valueOf(row.get("id")),branch,reservation)!=1) throw failure("TCC_TRY_STATE_CONFLICT");
                    return true;
                });
                if(!Boolean.TRUE.equals(prepared)) throw failure("TCC_TRY_REJECTED");
            } finally {BusinessActionContextUtil.clear();}
            return transactions.execute(status->{var row=lock(request);if(row.get("reservation_id")==null) throw failure("TCC_TRY_REJECTED");return result(row);});
        }
    }

    /** 资源反射声明需要prepare方法；真正Try只能从已鉴权网关进入。 */
    public boolean prepare(BusinessActionContext context){throw failure("TCC_DIRECT_PREPARE_FORBIDDEN");}

    /** 此方法由TC官方Fence在同事务内调用，不能映射成HTTP接口。 */
    public boolean confirm(BusinessActionContext context) {
        var row=guard(context,context.getXid(),context.getBranchId());
        inventory().confirmTried(text(row,"enterprise_id"),text(row,"warehouse_id"),operation("confirm",context),
                text(row,"allocation_id"),actionName,text(row,"allocation_id"),text(row,"attempt_id"),context.getXid(),context.getBranchId(),actionName);
        return true;
    }

    /** 仅释放原TRIED；空回滚的意图状态由外层Fence包装器持久化。 */
    public boolean cancel(BusinessActionContext context) {
        var row=guard(context,context.getXid(),context.getBranchId());
        inventory().cancelTried(text(row,"enterprise_id"),text(row,"warehouse_id"),operation("cancel",context),
                text(row,"allocation_id"),actionName,text(row,"allocation_id"),text(row,"attempt_id"),context.getXid(),context.getBranchId(),actionName);
        return true;
    }

    /** 路由行先锁定，确保迁移停写不能穿插在校验和Fence写入之间。 */
    @Override public Object prepareFence(String xid,Long branch,String action,Callback<Object> callback) {
        if(!actionName.equals(action)) throw failure("TCC_RESOURCE_MISMATCH");
        return transactions.execute(status->{guard(BusinessActionContextUtil.getContext(),xid,branch);return fence.prepareFence(xid,branch,action,callback);});
    }
    @Override public boolean commitFence(Method method,Object target,String xid,Long branch,Object[] args) {
        String action=args!=null && args.length==1 && args[0] instanceof BusinessActionContext context?context.getActionName():null;
        return finishFence(true,method,target,xid,branch,args,action);
    }
    @Override public boolean rollbackFence(Method method,Object target,String xid,Long branch,Object[] args,String action) {
        return finishFence(false,method,target,xid,branch,args,action);
    }
    private boolean finishFence(boolean commit,Method method,Object target,String xid,Long branch,Object[] args,String action) {
        if(target!=this || args==null || args.length!=1 || !(args[0] instanceof BusinessActionContext context)
                || !Objects.equals(action,context.getActionName()))
            throw failure("TCC_RESOURCE_MISMATCH");
        return Boolean.TRUE.equals(transactions.execute(status->{
            if(terminalReplay(commit,context,xid,branch)) return true;
            if(!actionName.equals(action)) throw failure("TCC_RESOURCE_MISMATCH");
            var row=guard(context,xid,branch);
            boolean done=commit?fence.commitFence(method,target,xid,branch,args):fence.rollbackFence(method,target,xid,branch,args,action);
            if(done && mapper().finished(text(row,"id"),branch,commit?"CONFIRMED":"CANCELLED")!=1)
                throw failure("TCC_TERMINAL_CONFLICT");
            return done;
        }));
    }
    /** 迁移后的历史回调只返回原证明结果；不重新调用库存业务、不更新原Fence时间。 */
    private boolean terminalReplay(boolean commit,BusinessActionContext context,String xid,Long branch) {
        if(branch==null || branch<=0 || !Objects.equals(xid,context.getXid()) || context.getBranchId()!=branch) throw failure("TCC_CONTEXT_MISMATCH");
        String enterprise=value(context,"enterpriseId"),warehouse=value(context,"warehouseId");
        var route=sessions.getMapper(WarehouseRouteMapper.class).lock(enterprise,warehouse);
        var row=mapper().lock(enterprise,warehouse,value(context,"allocationId"),value(context,"attemptId"));
        if(row==null) throw failure("TCC_CONTEXT_MISMATCH");
        var proof=mapper().proof(text(row,"id"));
        if(proof==null) return false;
        long epoch;
        try {epoch=new java.math.BigDecimal(value(context,"routeEpoch")).longValueExact();}
        catch(RuntimeException invalid) {throw failure("TCC_CONTEXT_MISMATCH");}
        if(route==null || !cellId.equals(route.get("cell_id")) || !Set.of("ACTIVE","QUIESCING","RETIRED").contains(route.get("state"))
                || !xid.equals(row.get("xid")) || !Objects.equals(branch,row.get("branch_id"))
                || !context.getActionName().equals(row.get("action_name")) || !value(context,"intentId").equals(row.get("id"))
                || !value(context,"requestDigest").equals(row.get("request_digest")) || !value(context,"cellId").equals(row.get("cell_id"))
                || epoch!=((Number)row.get("route_epoch")).longValue()) throw failure("TCC_CONTEXT_MISMATCH");
        var notice=RuntimeMessage.JSON.readValue(text(proof,"proof_json"),com.lrj.wms.contract.messaging.TcTerminalNotice.class);
        if(!TcTerminalService.resource(notice.clusterId(),cellId).equals(actionName)
                || !TcTerminalService.resource(notice.clusterId(),text(row,"cell_id")).equals(context.getActionName())
                || !xid.equals(proof.get("xid")) || !Objects.equals(branch,proof.get("branch_id"))
                || !context.getActionName().equals(proof.get("action_name"))) throw failure("TCC_RESOURCE_MISMATCH");
        int terminal=((Number)proof.get("terminal_status")).intValue();
        if(commit!=(terminal==9) || !TcTerminalService.matches(row,mapper().fence(xid,branch),terminal)) throw failure("TCC_TERMINAL_CONFLICT");
        return true;
    }
    /** 保留策略未获业务确认，不能让SDK按默认天数删除幂等Fence。 */
    @Override public int deleteFenceByDate(Date before){return 0;}

    private Map<String,Object> guard(BusinessActionContext context,String xid,Long branch) {
        if(context==null || branch==null || branch<=0 || !Objects.equals(xid,context.getXid()) || context.getBranchId()!=branch
                || !actionName.equals(context.getActionName())) throw failure("TCC_CONTEXT_MISMATCH");
        String enterprise=value(context,"enterpriseId"),warehouse=value(context,"warehouseId");
        long epoch;
        try {epoch=new java.math.BigDecimal(value(context,"routeEpoch")).longValueExact();}
        catch(RuntimeException invalid){throw failure("TCC_CONTEXT_MISMATCH");}
        requireRoute(enterprise,warehouse,value(context,"cellId"),epoch);
        var row=mapper().lock(enterprise,warehouse,value(context,"allocationId"),value(context,"attemptId"));
        if(row==null || !xid.equals(row.get("xid")) || !actionName.equals(row.get("action_name"))
                || !value(context,"intentId").equals(row.get("id")) || !value(context,"requestDigest").equals(row.get("request_digest"))
                || !cellId.equals(row.get("cell_id")) || epoch!=((Number)row.get("route_epoch")).longValue())
            throw failure("TCC_CONTEXT_MISMATCH");
        bindBranch(row,branch);
        return row;
    }
    private void bindBranch(Map<String,Object> row,long branch) {
        if(row.get("branch_id")==null) {
            if(mapper().bindBranch(text(row,"id"),branch)!=1) throw failure("TCC_BRANCH_CONFLICT");
            row.put("branch_id",branch);row.put("state","REGISTERED");
        } else if(((Number)row.get("branch_id")).longValue()!=branch) throw failure("TCC_BRANCH_CONFLICT");
    }
    private void requireRoute(String enterprise,String warehouse,String cell,long epoch) {
        var route=sessions.getMapper(WarehouseRouteMapper.class).lock(enterprise,warehouse);
        if(!cellId.equals(cell) || route==null || !"ACTIVE".equals(route.get("state")) || !cellId.equals(route.get("cell_id"))
                || epoch!=((Number)route.get("route_epoch")).longValue()) throw failure("STALE_ROUTE");
    }
    private List<ReservationLineInput> reservationLines(WarehouseTryRequest r) {
        var master=sessions.getMapper(MasterdataHttpMapper.class);
        var lines=new ArrayList<ReservationLineInput>();
        for(var line:r.lines()) {
            var sku=master.getSku(r.enterpriseId(),line.skuId());
            if(sku==null || !"ACTIVE".equals(sku.get("state")) || !line.baseUnit().equals(sku.get("base_unit"))) throw failure("TCC_SKU_MISMATCH");
            boolean lotEnabled=((Number)sku.get("lot_enabled")).intValue()!=0;
            if(lotEnabled) {
                var lot=master.getLot(r.enterpriseId(),r.warehouseId(),line.lotId());
                if(lot==null || !r.ownerId().equals(lot.get("owner_id")) || !line.skuId().equals(lot.get("sku_id"))) throw failure("TCC_LOT_MISMATCH");
                if(line.minRemainingDays()>0 || ((Number)sku.get("expiry_enabled")).intValue()!=0) {
                    var expiry=lot.get("expires_at");
                    if(expiry==null || !com.lrj.wms.runtime.db.DatabaseInstants.require(expiry).isAfter(clock.instant().plus(java.time.Duration.ofDays(line.minRemainingDays()))))
                        throw failure("TCC_EXPIRY_REJECTED");
                }
            } else if(!"NO_LOT".equals(line.lotId()) || line.minRemainingDays()>0) throw failure("TCC_LOT_MISMATCH");
            lines.add(new ReservationLineInput(StockBucketKey.of(r.enterpriseId(),r.warehouseId(),r.ownerId(),line.sourceLocationId(),
                    line.skuId(),line.lotId(),"GOOD"),Quantity.of(line.qty(),((Number)sku.get("quantity_scale")).intValue()),line.orderLineId()));
        }
        return lines;
    }
    private String applicationData(Map<String,Object> row) {
        return RuntimeMessage.JSON.writeValueAsString(Map.of(Constants.TX_ACTION_CONTEXT,Map.of(
                "enterpriseId",row.get("enterprise_id"),"warehouseId",row.get("warehouse_id"),"allocationId",row.get("allocation_id"),
                "attemptId",row.get("attempt_id"),"intentId",row.get("id"),"requestDigest",row.get("request_digest"),
                "cellId",cellId,"routeEpoch",row.get("route_epoch"),Constants.USE_COMMON_FENCE,true)));
    }
    private WarehouseTryResult result(Map<String,Object> row) {
        return new WarehouseTryResult(text(row,"xid"),((Number)row.get("branch_id")).longValue(),text(row,"action_name"),
                text(row,"reservation_id"),((Number)row.get("route_epoch")).longValue(),text(row,"allocation_id"),text(row,"attempt_id"),text(row,"state"));
    }
    private RuntimeTccMapper mapper(){return sessions.getMapper(RuntimeTccMapper.class);}
    private Map<String,Object> lock(WarehouseTryRequest r){return mapper().lock(r.enterpriseId(),r.warehouseId(),r.allocationId(),r.attemptId());}
    private InventoryApplicationService inventory(){return new InventoryApplicationService(sessions,clock);}
    private static String operation(String phase,BusinessActionContext c){return CommandDigest.v1Parts("runtime-tcc-"+phase,c.getXid(),Long.toString(c.getBranchId()));}
    private static String text(Map<String,Object> row,String key){Object v=row.get(key);if(v==null)throw failure("TCC_CONTEXT_MISMATCH");return v.toString();}
    private static String value(BusinessActionContext c,String key){Object v=c.getActionContext(key);if(v==null || v.toString().isBlank())throw failure("TCC_CONTEXT_MISMATCH");return v.toString();}
    private static InventoryException failure(String code){return new InventoryException(code,"TCC原分支尚未完成或上下文不一致，请保留原XID恢复");}
}
