package com.lrj.wms.contract.messaging;

import java.math.BigDecimal;
import java.util.*;

/** 每个收货批次的累计合格/不合格身份快照，未列出的原身份继续HOLD。 */
public record SerialQualityObservation(int schemaVersion,List<String> acceptedSerials,List<String> rejectedSerials) {
    public SerialQualityObservation {
        if(schemaVersion!=1 || acceptedSerials==null || rejectedSerials==null
                || acceptedSerials.size()+rejectedSerials.size()<1 || acceptedSerials.size()+rejectedSerials.size()>200)
            throw new IllegalArgumentException("质检身份须为V1且合计1至200条");
        acceptedSerials=canonical(acceptedSerials);rejectedSerials=canonical(rejectedSerials);
        if(!Collections.disjoint(acceptedSerials,rejectedSerials)) throw new IllegalArgumentException("同一身份不能同时合格和不合格");
    }
    private static List<String> canonical(List<String> serials) {
        return serials.isEmpty()?List.of():new SerialReceiptObservation(1,serials).serialIds();
    }
    /** 数量与名单同义，不能借等量替换隐藏身份变更。 */
    public void requireDecision(ReceiptQualityDecision decision) {
        if(decision.acceptedQty().compareTo(BigDecimal.valueOf(acceptedSerials.size()))!=0
                || decision.rejectedQty().compareTo(BigDecimal.valueOf(rejectedSerials.size()))!=0)
            throw new IllegalArgumentException("质检累计数量与明确身份不一致");
    }
    /** 来源和库存分别用自己保存的原批次校验，禁止跨批借身份。 */
    public void requireReceipt(SerialReceiptObservation receipt) {
        if(!receipt.serialIds().containsAll(acceptedSerials) || !receipt.serialIds().containsAll(rejectedSerials))
            throw new IllegalArgumentException("质检身份不属于原收货批次");
    }
    /** 未列出的原批次身份不作推断，明确保持HOLD。 */
    public String quality(String serial) {return acceptedSerials.contains(serial)?"GOOD":rejectedSerials.contains(serial)?"REJECTED":"HOLD";}
}
