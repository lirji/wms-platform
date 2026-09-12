package com.lrj.wms.fulfillment;

/** TC集群和TM身份来自受控配置，不能由HTTP调用方提供或随恢复猜测。 */
public record TcEvidenceScope(String clusterId, String applicationId, String transactionGroup) {
    public TcEvidenceScope {
        if (clusterId == null || !clusterId.matches("[A-Za-z0-9._-]{1,64}")
                || !"wms-fulfillment".equals(applicationId)
                || transactionGroup == null || !transactionGroup.matches("[A-Za-z0-9._-]{1,32}")) {
            throw new IllegalArgumentException("TC审计必须配置明确集群、wms-fulfillment身份和事务分组");
        }
    }
}
