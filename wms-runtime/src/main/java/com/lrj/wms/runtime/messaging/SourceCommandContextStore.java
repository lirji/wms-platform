package com.lrj.wms.runtime.messaging;

import com.lrj.wms.contract.messaging.StockPostingContext;
import com.lrj.wms.runtime.command.CommandConflictException;
import com.lrj.wms.runtime.messaging.persistence.SourceContextMapper;
import com.lrj.wms.runtime.observability.RequestCorrelationFilter;
import org.apache.ibatis.session.SqlSession;
import tools.jackson.databind.node.ObjectNode;

/** 完整过账上下文与来源命令/Outbox在同一T1提交，不能为历史重放猜测原始库存维度。 */
public final class SourceCommandContextStore {
    private final SqlSession session;
    public SourceCommandContextStore(SqlSession session) { this.session = session; }

    /** 首次绑定保留请求关联；重试只比较业务维度，不改第一次的actor/执行时间/requestId。 */
    public void bind(String enterpriseId, String warehouseId, String commandId, StockPostingContext context, boolean replayed) {
        var mapper = session.getMapper(SourceContextMapper.class);
        var command = mapper.lockCommand(enterpriseId, warehouseId, commandId);
        if (command == null) throw new IllegalStateException("来源命令不存在");
        context.requireForAction(String.valueOf(command.get("action")));
        var payload = RuntimeMessage.JSON.readTree(String.valueOf(command.get("payload_json")));
        if (!(payload instanceof ObjectNode object)) throw new IllegalStateException("来源命令正文无效");
        var supplied = RuntimeMessage.JSON.valueToTree(context);
        if (object.has("postingContext")) {
            if (!object.path("postingContext").equals(supplied)) throw new CommandConflictException();
            return;
        }
        if (replayed) throw new MissingCommandContextException();
        object.put("schemaVersion", 1);
        object.set("postingContext", supplied);
        object.put("postingContextDigest", RuntimeMessage.contentHash(supplied.toString()));
        object.put("requestId", RequestCorrelationFilter.currentId());
        String body = object.toString();
        if (mapper.bindCommand(enterpriseId, warehouseId, commandId, body) != 1
                || mapper.bindOutbox(enterpriseId, warehouseId, commandId, body) != 1) {
            throw new IllegalStateException("来源命令与待发布事件必须唯一且同时绑定");
        }
    }
}
