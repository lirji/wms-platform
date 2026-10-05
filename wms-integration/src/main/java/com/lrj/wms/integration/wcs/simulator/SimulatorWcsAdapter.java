package com.lrj.wms.integration.wcs.simulator;

import com.lrj.wms.integration.wcs.error.WcsAdapterException;
import com.lrj.wms.integration.wcs.model.WcsCommand;
import com.lrj.wms.integration.wcs.model.WcsCommandState;
import com.lrj.wms.integration.wcs.model.WcsCommandView;
import com.lrj.wms.integration.wcs.model.WcsDispatchResult;
import com.lrj.wms.integration.wcs.model.WcsReceipt;
import com.lrj.wms.integration.wcs.model.WcsReceiptResult;
import com.lrj.wms.integration.wcs.port.WcsCommandPort;
import com.lrj.wms.integration.wcs.port.WcsQueryPort;
import com.lrj.wms.integration.wcs.port.WcsReceiptPort;

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
    public static final String STATE_DISPATCHED = WcsCommandState.DISPATCHED.code();
    public static final String STATE_UNKNOWN = WcsCommandState.UNKNOWN.code();
    public static final String STATE_COMPLETED = WcsCommandState.COMPLETED.code();
    public static final String STATE_FAILED = WcsCommandState.FAILED.code();

    private final Clock clock;
    private final Map<String, StoredCommand> commands = new ConcurrentHashMap<>();
    private final Map<String, WcsReceipt> receipts = new ConcurrentHashMap<>();

    /** 显式接收 SimulatorWcsAdapter 的协作对象或配置，保持本实例使用的依赖与创建入口一致。 */
    public SimulatorWcsAdapter(Clock clock) {
        this.clock = clock;
    }

    /** 明确返回适配器实现身份，调用方才能区分隔离模拟与现场实现。 */
    public String implementation() {
        return IMPLEMENTATION;
    }

    /** 相同命令身份只派发一次；重派必须比较原载荷，不能改内容后复用身份。 */
    @Override
    public WcsDispatchResult dispatch(WcsCommand command) {
        Instant now = clock.instant();
        StoredCommand created = new StoredCommand(command, WcsCommandState.DISPATCHED, null, now);
        StoredCommand existing = commands.putIfAbsent(command.identityKey(), created);
        if (existing == null) {
            return new WcsDispatchResult(command.deviceCommandId(), STATE_DISPATCHED, false);
        }
        if (!existing.command.samePayload(command)) {
            throw new WcsAdapterException("COMMAND_CONFLICT", "同deviceCommandId不能改内容重派");
        }
        return new WcsDispatchResult(
                existing.command.deviceCommandId(), existing.state.code(), true);
    }

    /** 只读取隔离模拟器的命令观察，缺失命令不能推断为设备已完成。 */
    @Override
    public Optional<WcsCommandView> query(
            String enterpriseId, String warehouseId, String deviceCommandId) {
        if (enterpriseId == null || warehouseId == null || deviceCommandId == null) {
            return Optional.empty();
        }
        String key = enterpriseId + '\u001f' + warehouseId + '\u001f' + deviceCommandId;
        StoredCommand stored = commands.get(key);
        if (stored == null) {
            return Optional.empty();
        }
        return Optional.of(
                new WcsCommandView(
                        stored.command.deviceCommandId(),
                        stored.state.code(),
                        stored.command.action(),
                        stored.command.sourceTaskId(),
                        stored.command.qty(),
                        stored.receiptEventId,
                        stored.updatedAt));
    }

    /** 先验证回执再同步发布事件去重记录与命令状态，拒绝不占用重放身份。 */
    @Override
    public synchronized WcsReceiptResult accept(WcsReceipt receipt) {
        StoredCommand stored = commands.get(receipt.identityKey());
        if (stored == null) {
            throw new WcsAdapterException("UNKNOWN_COMMAND", "回执对应的设备命令不存在");
        }
        // 先完整校验再占用事件身份；拒绝不能污染重放记录或变成下一次调用的成功结果。
        WcsCommandState nextState = WcsCommandState.receiptState(receipt.resultState());
        // 单次处理同时发布幂等记录与命令观察，竞争重放不能读取第一次处理的中间态。
        WcsReceipt previous = receipts.putIfAbsent(receipt.eventId(), receipt);
        if (previous != null) {
            if (!previous.identityKey().equals(receipt.identityKey())
                    || !previous.resultState().equals(receipt.resultState())
                    || previous.actualQty().compareTo(receipt.actualQty()) != 0) {
                throw new WcsAdapterException("RECEIPT_CONFLICT", "同eventId不能改内容重放");
            }
            return new WcsReceiptResult(
                    stored.command.deviceCommandId(),
                    previous.eventId(),
                    stored.state.code(),
                    true);
        }
        StoredCommand updated =
                new StoredCommand(stored.command, nextState, receipt.eventId(), clock.instant());
        commands.put(receipt.identityKey(), updated);
        return new WcsReceiptResult(
                updated.command.deviceCommandId(), receipt.eventId(), nextState.code(), false);
    }

    private static final class StoredCommand {
        private final WcsCommand command;
        private final WcsCommandState state;
        private final String receiptEventId;
        private final Instant updatedAt;

        private StoredCommand(
                WcsCommand command,
                WcsCommandState state,
                String receiptEventId,
                Instant updatedAt) {
            this.command = command;
            this.state = state;
            this.receiptEventId = receiptEventId;
            this.updatedAt = updatedAt;
        }
    }
}
