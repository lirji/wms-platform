package com.lrj.wms.inbound.receipt;

import com.lrj.wms.contract.messaging.*;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import com.lrj.wms.runtime.messaging.persistence.SourceContextMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.apache.ibatis.session.SqlSession;

/** 原行和批次质量锁内受理具体上架身份；网络结果未知时保留原任务，不释放身份再做一次。 */
public final class ReceiptSerialPutawayService {
    private final SqlSession session;
    private final Clock clock;
    public ReceiptSerialPutawayService(SqlSession session,Clock clock) {this.session=session;this.clock=clock;}

    /** 当前已生效质量命令才证明哪些序列号合格，不能只凭accepted_qty猜测。 */
    public void claim(String e,String w,String receipt,String task,String command,SerialStockSelection selection,Map<String,Object> quality) {
        var source=session.getMapper(SourceContextMapper.class).lockCommand(e,w,String.valueOf(quality.get("active_command_id")));
        if(source==null || !"QUALITY".equals(source.get("action"))) throw new InboundException("SERIAL_QUALITY_CONTEXT_REQUIRED","缺少已生效的原身份质检命令");
        var payload=RuntimeMessage.JSON.readTree(String.valueOf(source.get("payload_json")));
        if(!payload.hasNonNull("serialQualityObservation")) throw new InboundException("SERIAL_QUALITY_CONTEXT_REQUIRED","旧质量命令没有可信身份清单");
        var observed=RuntimeMessage.JSON.treeToValue(payload.path("serialQualityObservation"),SerialQualityObservation.class);
        var decision=RuntimeMessage.JSON.treeToValue(payload.path("qualityDecision"),ReceiptQualityDecision.class);
        observed.requireDecision(decision);
        if(!receipt.equals(decision.receiptCommandId()) || decision.sourceVersion()!=((Number)quality.get("applied_version")).longValue()
                || !observed.acceptedSerials().containsAll(selection.serialIds()))
            throw new InboundException("SERIAL_NOT_ACCEPTED","所选身份未在本批当前版本判为合格");
        var mapper=session.getMapper(ReceiptSerialPutawayMapper.class);
        for(String serial:selection.serialIds()) {
            mapper.insert(Map.of("id",UUID.randomUUID().toString(),"e",e,"w",w,"receipt",receipt,"serial",serial,"task",task,"command",command,"now",Timestamp.from(clock.instant())));
            var row=mapper.lock(e,w,receipt,serial);
            if(row==null || !task.equals(row.get("task_id")) || !command.equals(row.get("putaway_command_id")))
                throw new InboundException("SERIAL_ALREADY_PUTAWAY","同一收货身份不能被其他上架任务重复领取");
        }
    }
}
