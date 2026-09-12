package com.lrj.wms.contract.tcc;

/** 已落库Try的原分支身份；CONFIRMED由TC回调，不能由HTTP成功推断。 */
public record WarehouseTryResult(String xid,long branchId,String actionName,String reservationId,long routeEpoch,
        String allocationId,String attemptId,String state) { }
