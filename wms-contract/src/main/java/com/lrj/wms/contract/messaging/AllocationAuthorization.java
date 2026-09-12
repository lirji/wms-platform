package com.lrj.wms.contract.messaging;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** 履约成功屏障的不可变仓级授权；生产者先核验真实TC证据与全部原分支确认。 */
public record AllocationAuthorization(int authorizationSchemaVersion, String enterpriseId, String warehouseId,
        String fulfillmentId, String ownerId, String allocationId, String attemptId, String xid,
        String authorizationId, String tcTerminalEvidenceRef, String participantSetHash,
        TcProof tcProof, List<Participant> participants, List<Line> lines) {
    public AllocationAuthorization {
        if (authorizationSchemaVersion != 1) throw new IllegalArgumentException("未知授权契约版本");
        for (String id : new String[]{enterpriseId, warehouseId, fulfillmentId, ownerId, allocationId, attemptId, authorizationId}) id(id, 64);
        id(xid, 128); id(tcTerminalEvidenceRef, 256);
        if (participantSetHash == null || !participantSetHash.matches("[a-f0-9]{64}") || tcProof == null)
            throw new IllegalArgumentException("缺少成功屏障来源");
        participants = bounded(participants); lines = bounded(lines);
        var warehouses = new HashSet<String>();
        boolean own = false;
        for (Participant participant : participants) {
            if (!warehouses.add(participant.warehouseId()) || !allocationId.equals(participant.allocationId()))
                throw new IllegalArgumentException("参与仓重复或分配身份不一致");
            own |= warehouseId.equals(participant.warehouseId());
        }
        if (!own || !hash(String.join("\u001f", warehouses.stream().sorted().toList())).equals(participantSetHash))
            throw new IllegalArgumentException("当前仓或固定参与者摘要不一致");
        var ids = new HashSet<String>();
        for (Line line : lines) if (!ids.add(line.orderLineId())) throw new IllegalArgumentException("仓级订单行重复");
        if (!authorizationKey(enterpriseId, warehouseId, attemptId, allocationId).equals(authorizationId)
                || !evidenceReference(tcProof, xid).equals(tcTerminalEvidenceRef))
            throw new IllegalArgumentException("授权或证据引用不属于原始身份");
    }

    /** TC来源来自受控绑定；原证据正文由适配器再次核对XID、终态码、集群、应用和事务组。 */
    public record TcProof(String clusterId, String applicationId, String transactionGroup, String terminalEvidence) {
        public TcProof {
            if (clusterId == null || !clusterId.matches("[A-Za-z0-9._-]{1,64}")
                    || !"wms-fulfillment".equals(applicationId)
                    || transactionGroup == null || !transactionGroup.matches("[A-Za-z0-9._-]{1,32}"))
                throw new IllegalArgumentException("TC来源范围无效");
            id(terminalEvidence, 16384);
        }
    }

    /** 只有带原分支/资源/路由和确认版本的固定参与仓才能出现在授权中。 */
    public record Participant(String warehouseId, String allocationId, String reservationId,
            long branchId, String actionName, long routeEpoch, long confirmedVersion) {
        public Participant {
            for (String id : new String[]{warehouseId, allocationId, reservationId, actionName}) id(id, 64);
            if (branchId < 1 || routeEpoch < 0 || confirmedVersion < 1) throw new IllegalArgumentException("仓确认身份不完整");
        }
    }

    /** 原业务订单行而非出库内部行ID，数量精度与持久化DECIMAL(20,6)一致。 */
    public record Line(String orderLineId, String skuId, BigDecimal qty, String baseUnit) {
        public Line {
            id(orderLineId, 64); id(skuId, 64); id(baseUnit, 32);
            if (qty == null || qty.signum() <= 0) throw new IllegalArgumentException("数量必须大于零");
            qty = qty.stripTrailingZeros();
            if (qty.scale() > 6 || qty.precision() - qty.scale() > 14) throw new IllegalArgumentException("数量超出持久化精度");
        }
    }

    /** 同一仓级分配始终沿用一个授权身份；长度前缀避免业务ID分隔符造成拼接歧义。 */
    public static String authorizationKey(String enterprise, String warehouse, String attempt, String allocation) {
        return framedHash(List.of("ALLOCATION_AUTHORIZATION_V1", enterprise, warehouse, attempt, allocation));
    }

    /** 引用包含环境与TC身份，不能把其他集群同名XID当作本次提交证明。 */
    public static String evidenceReference(TcProof proof, String xid) {
        return "tc-v1:" + framedHash(List.of(proof.clusterId(), proof.applicationId(), proof.transactionGroup(), xid, "9"));
    }

    private static String framedHash(List<String> values) {
        var body = new StringBuilder();
        for (String value : values) body.append(value.length()).append(':').append(value);
        return hash(body.toString());
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void id(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("授权字段为空或超长");
    }
    private static <T> List<T> bounded(List<T> values) {
        if (values == null || values.isEmpty() || values.size() > 200) throw new IllegalArgumentException("授权清单必须有界且非空");
        return List.copyOf(values);
    }
}
