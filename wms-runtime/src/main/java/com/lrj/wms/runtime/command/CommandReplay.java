package com.lrj.wms.runtime.command;

import java.math.BigDecimal;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 入出库共用的来源命令协议校验；效果身份已经覆盖企业/仓/动作/单据/分批/行。 */
public final class CommandReplay {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private CommandReplay() { }

    /** 重放必须保持事实和精确数量相同，3与3.000000属于同一数量。 */
    public static void requireSamePayload(Map<String, Object> command, String action, String effectId, BigDecimal qty) {
        if (command == null) throw new IllegalStateException("来源命令未持久化");
        if (!effectId.equals(command.get("business_effect_key")) || !action.equals(command.get("action"))) {
            throw new CommandConflictException();
        }
        try {
            var stored = JSON.readTree(String.valueOf(command.get("payload_json"))).path("qty");
            BigDecimal value = stored.isNumber() ? stored.decimalValue() : new BigDecimal(stored.asString());
            if (qty != null && value.compareTo(qty) != 0) throw new CommandConflictException();
        } catch (tools.jackson.core.JacksonException | NumberFormatException error) {
            throw new IllegalStateException("来源命令数量记录无效", error);
        }
    }

    /** 用于取消剩余量等由服务端决定数量的命令，重放恢复首次冻结的数量。 */
    public static BigDecimal quantity(Map<String, Object> command) {
        var stored = JSON.readTree(String.valueOf(command.get("payload_json"))).path("qty");
        return stored.isNumber() ? stored.decimalValue() : new BigDecimal(stored.asString());
    }

    /** 由JSON库转义命令身份，避免引号等合法字符破坏持久化消息。 */
    public static String payload(String commandId, BigDecimal qty) {
        return JSON.writeValueAsString(Map.of("commandId", commandId, "qty", qty.toPlainString()));
    }
    /** 同一任务中的分批身份稳定编码；任务身份也参与，防止同命令跨任务误重放。 */
    public static String partId(String taskId, String partId) {
        try {
            byte[] canonical = JSON.writeValueAsBytes(java.util.List.of(taskId, partId));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
