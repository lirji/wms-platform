package com.lrj.wms.contract.cancellation;

import com.lrj.wms.contract.tcc.TcTerminalNotice;
import com.lrj.wms.contract.tcc.WarehouseTryRequest;

/** 已提交后的业务取消，只携带原冻结分配和TC证明，不改变TCC终态。 */
public record CommittedCancellation(
        int schemaVersion,
        String cancellationId,
        String actorId,
        WarehouseTryRequest request,
        TcTerminalNotice terminal) {
    public static final String EVENT = "CommittedCancellationRequestedV1";
    public static final String RESULT = "CommittedCancellationResultV1";

    /** 在不可变契约的构造边界统一处理输入，保证默认值、校验与字段复制不在各调用点分叉。 */
    public CommittedCancellation {
        if (schemaVersion != 1
                || cancellationId == null
                || cancellationId.isBlank()
                || cancellationId.length() > 64
                || actorId == null
                || actorId.isBlank()
                || actorId.length() > 64
                || request == null
                || terminal == null
                || terminal.terminalStatus().intValue() != 9
                || !request.attemptId().equals(terminal.attemptId())
                || !request.allocationId().equals(terminal.allocationId()))
            throw new IllegalArgumentException("原取消或已提交证据不一致");
    }
}
