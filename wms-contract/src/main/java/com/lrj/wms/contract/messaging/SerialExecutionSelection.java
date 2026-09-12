package com.lrj.wms.contract.messaging;

import java.math.BigDecimal;
import java.util.*;

/** 出库选择固定实物身份及归属代际，旧周期的同名SN不能冒充当前库存。 */
public record SerialExecutionSelection(Number schemaVersion,List<Identity> identities) {
    public SerialExecutionSelection {
        if(!(schemaVersion instanceof Integer || schemaVersion instanceof Long) || schemaVersion.longValue()!=1
                || identities==null || identities.isEmpty() || identities.size()>200)
            throw new IllegalArgumentException("出库身份选择须为V1且包含1至200个身份");
        schemaVersion=1;
        var sorted=new TreeMap<String,Identity>();
        for(var identity:identities) {
            if(identity==null || sorted.putIfAbsent(identity.serialId(),identity)!=null)
                throw new IllegalArgumentException("不能重复选择同一规范化序列号");
        }
        identities=List.copyOf(sorted.values());
    }

    /** 每个选定身份只代表一个基本单位，不能用小数或额外数量替代身份。 */
    public void requireQuantity(BigDecimal quantity) {
        if(quantity==null || quantity.compareTo(BigDecimal.valueOf(identities.size()))!=0)
            throw new IllegalArgumentException("出库数量必须等于所选身份数量");
    }

    /** Number保留JSON原类型后校验，防小数epoch被反序列化截断后通过。 */
    public record Identity(String serialId,Number ownerEpoch) {
        public Identity {
            if(!(ownerEpoch instanceof Integer || ownerEpoch instanceof Long) || ownerEpoch.longValue()<0)
                throw new IllegalArgumentException("归属代际必须是非负整数");
            serialId=new SerialReceiptObservation(1,List.of(Objects.requireNonNull(serialId,"序列号不能为空"))).serialIds().getFirst();
            ownerEpoch=ownerEpoch.longValue();
        }
    }
}
