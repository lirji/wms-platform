package com.lrj.wms.integration.wcs;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 明确标识的内存 WCS simulator。只用于隔离测试，不是真实设备、现场协议或生产适配器。
 * 不写库存库，不签发 permit，不派发物理动作。
 */
public final class SimulatorWcsAdapter implements WcsCommandPort, WcsQueryPort, WcsReceiptPort {
    public static final String IMPLEMENTATION = "SIMULATOR";
    public static final String STATE_DISPATCHED = "DISPATCHED";
    public static final String STATE_UNKNOWN = "UNKNOWN";
    public static final String STATE_COMPLETED = "COMPLETED";
    public static final String STATE_FAILED = "FAILED";

    private final Clock clock;
    private final Map<String, StoredCommand> commands = new ConcurrentHashMap<>();
    private final Map<String, WcsReceipt> receipts = new ConcurrentHashMap<>();

    public SimulatorWcsAdapter(Clock clock) {
        this.clock = clock;
    }

    public String implementation() {
        return IMPLEMENTATION;
    }

    @Override
    public WcsDispatchResult dispatch(WcsCommand command) {
        Instant now = clock.instant();
        StoredCommand created = new StoredCommand(command, STATE_DISPATCHED, null, now);
        StoredCommand existing = commands.putIfAbsent(command.identityKey(), created);
        if (existing == null) {
            return new WcsDispatchResult(command.deviceCommandId(), STATE_DISPATCHED, false);
        }
        if (!existing.command.samePayload(command)) {
            throw new WcsAdapterException("COMMAND_CONFLICT", "同deviceCommandId不能改内容重派");
        }
        return new WcsDispatchResult(existing.command.deviceCommandId(), existing.state, true);
    }

    @Override
    public Optional<WcsCommandView> query(String enterpriseId, String warehouseId, String deviceCommandId) {
        if (enterpriseId == null || warehouseId == null || deviceCommandId == null) {
            return Optional.empty();
        }
        String key = enterpriseId + '\u001f' + warehouseId + '\u001f' + deviceCommandId;
        StoredCommand stored = commands.get(key);
        if (stored == null) {
            return Optional.empty();
        }
        return Optional.of(new WcsCommandView(stored.command.deviceCommandId(), stored.state, stored.command.action(),
                stored.command.sourceTaskId(), stored.command.qty(), stored.receiptEventId, stored.updatedAt));
    }

    @Override
    public WcsReceiptResult accept(WcsReceipt receipt) {
        StoredCommand stored = commands.get(receipt.identityKey());
        if (stored == null) {
            throw new WcsAdapterException("UNKNOWN_COMMAND", "回执对应的设备命令不存在");
        }
        WcsReceipt previous = receipts.putIfAbsent(receipt.eventId(), receipt);
        if (previous != null) {
            if (!previous.identityKey().equals(receipt.identityKey())
                    || !previous.resultState().equals(receipt.resultState())
                    || previous.actualQty().compareTo(receipt.actualQty()) != 0) {
                throw new WcsAdapterException("RECEIPT_CONFLICT", "同eventId不能改内容重放");
            }
            return new WcsReceiptResult(stored.command.deviceCommandId(), previous.eventId(), stored.state, true);
        }
        String nextState = switch (receipt.resultState()) {
            case "FAILED" -> STATE_FAILED;
            case "UNKNOWN" -> STATE_UNKNOWN;
            case "COMPLETED" -> STATE_COMPLETED;
            default -> throw new WcsAdapterException("INVALID_RECEIPT", "不支持的回执状态");
        };
        StoredCommand updated = new StoredCommand(stored.command, nextState, receipt.eventId(), clock.instant());
        commands.put(receipt.identityKey(), updated);
        return new WcsReceiptResult(updated.command.deviceCommandId(), receipt.eventId(), nextState, false);
    }

    private static final class StoredCommand {
        private final WcsCommand command;
        private final String state;
        private final String receiptEventId;
        private final Instant updatedAt;

        private StoredCommand(WcsCommand command, String state, String receiptEventId, Instant updatedAt) {
            this.command = command;
            this.state = state;
            this.receiptEventId = receiptEventId;
            this.updatedAt = updatedAt;
        }
    }
}
