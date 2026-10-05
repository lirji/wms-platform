package com.lrj.wms.security.authorization;

import com.lrj.authz.protocol.ScopeAccessDtos.Plan;

import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 仅后端创建的单请求权限上下文；保留原Access证据，不改写签名声明或跨请求复用范围。 */
final class WmsCentralJwt extends Jwt implements AutoCloseable {
    private final WmsCentralAuthorization authorization;
    private final WmsCentralBindings.Binding binding;
    private final String enterprise;
    private final Plan initial;
    private final Map<String, Plan> plans = new HashMap<>();
    private boolean active = true;

    WmsCentralJwt(
            Jwt jwt,
            WmsCentralAuthorization authorization,
            WmsCentralBindings.Binding binding,
            Plan initial,
            String enterprise) {
        super(
                jwt.getTokenValue(),
                jwt.getIssuedAt(),
                jwt.getExpiresAt(),
                jwt.getHeaders(),
                jwt.getClaims());
        this.authorization = authorization;
        this.binding = binding;
        this.enterprise = enterprise;
        this.initial = initial;
        plans.put(binding.capability(), initial);
    }

    String enterprise() {
        current();
        return enterprise;
    }

    Set<String> warehouses() {
        current();
        return WmsCentralAuthorization.warehouseIds(initial);
    }

    Set<String> scopes() {
        current();
        return Set.of(binding.legacy());
    }

    /** 业务Owner读到的单据仓继续按当前动作判权，不借旧JWT或读取能力扩大写范围。 */
    void requireWarehouse(String warehouse) {
        current();
        Plan plan =
                WmsCentralBindings.WAREHOUSE.equals(binding.resource())
                        ? initial
                        : require(binding.legacy(), WmsCentralBindings.WAREHOUSE);
        if (!WmsCentralAuthorization.warehouseIds(plan).contains(warehouse))
            throw new WarehouseForbiddenException(warehouse);
    }

    void requireScope(String legacy) {
        current();
        if (!binding.legacy().equals(legacy)) require(legacy, binding.resource());
    }

    private Plan require(String legacy, String resource) {
        String capability = authorization.bindings.capability(legacy, resource);
        Plan plan =
                plans.computeIfAbsent(
                        capability,
                        value ->
                                authorization.plan(
                                        getTokenValue(),
                                        value,
                                        resource,
                                        initial.context().membershipGeneration(),
                                        true));
        WmsCentralAuthorization.requireSameIdentity(initial.context(), plan.context());
        if (!Instant.parse(plan.validUntil()).isAfter(Instant.now())) {
            throw new CentralAuthorizationException(
                    CentralAuthorizationException.Reason.UNAVAILABLE);
        }
        return plan;
    }

    private void current() {
        if (!active || !Instant.parse(initial.validUntil()).isAfter(Instant.now())) {
            throw new CentralAuthorizationException(
                    CentralAuthorizationException.Reason.UNAVAILABLE);
        }
    }

    /** 请求结束即失效；异步业务消息不能携带本请求的身份或允许缓存。 */
    @Override
    public void close() {
        active = false;
        plans.clear();
    }

    /** 身份诊断只输出既定脱敏表示，原始 JWT 不能进入日志。 */
    @Override
    public String toString() {
        return "WmsCentralJwt[credentials=redacted]";
    }
}
