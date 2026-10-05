package com.lrj.wms.security.authorization;

import com.lrj.authz.protocol.CentralAccessDtos.Check;
import com.lrj.authz.protocol.NavigationDtos;
import com.lrj.authz.protocol.ScopeAccessDtos.Plan;
import com.lrj.authz.protocol.ScopeDtos;
import com.lrj.authz.sdk.AccessDeniedException;
import com.lrj.authz.sdk.CentralAccessClient;
import com.lrj.authz.sdk.CentralAccessException;

import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** 固定双身份的WMS适配；远程调用在业务事务之前，每次请求独立判权且不缓存ALLOW。 */
final class WmsCentralAuthorization implements AutoCloseable {
    private final WmsCentralSettings settings;
    private final CentralAccessClient client;
    private final Semaphore capacity;
    private final java.util.concurrent.ThreadPoolExecutor navigationChecks;
    final WmsCentralBindings bindings = new WmsCentralBindings();

    WmsCentralAuthorization(WmsCentralSettings settings) {
        this.settings = settings;
        this.client =
                new CentralAccessClient(
                        settings.baseUrl(),
                        settings.credential(),
                        settings.application(),
                        settings.environment(),
                        settings.connectTimeout(),
                        settings.timeout());
        capacity = new Semaphore(settings.maximumConcurrent());
        // 提示接口需复核多种能力；有界并发避免49次串行HTTP超过预算，并保留业务调用容量。
        int parallel = Math.min(4, settings.maximumConcurrent());
        navigationChecks =
                new java.util.concurrent.ThreadPoolExecutor(
                        parallel,
                        parallel,
                        0,
                        java.util.concurrent.TimeUnit.SECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(50),
                        Thread.ofPlatform().daemon(true).name("wms-navigation-check-", 0).factory(),
                        new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    WmsCentralJwt authorize(Jwt original, WmsCentralBindings.Binding binding) {
        verifyEnterprise(original);
        Plan plan =
                plan(
                        original.getTokenValue(),
                        binding.capability(),
                        binding.resource(),
                        null,
                        true);
        return new WmsCentralJwt(original, this, binding, plan, settings.enterprise());
    }

    Plan plan(String token, String capability, String resource, Long generation, boolean required) {
        return remote(
                () -> {
                    var check =
                            new Check(
                                    settings.tenant(),
                                    generation,
                                    UUID.randomUUID().toString(),
                                    capability,
                                    resource);
                    return required
                            ? client.requireScope(token, check)
                            : client.scopePlan(token, check);
                });
    }

    /** 不把Token中的owner当中央tenant；签名企业声明也必须匹配受控部署映射。 */
    private void verifyEnterprise(Jwt jwt) {
        if (!"access-token".equals(jwt.getClaims().get("tokenType"))) {
            throw new CentralAuthorizationException(
                    CentralAuthorizationException.Reason.UNAUTHENTICATED);
        }
        if (!settings.enterprise().equals(jwt.getClaims().get("enterprise_id"))) {
            throw new CentralAuthorizationException(CentralAuthorizationException.Reason.DENIED);
        }
    }

    /** 同一Grant中所有范围条件先取交集，再合并完整允许路径，禁止能力/范围交叉拼接。 */
    static Set<String> warehouseIds(Plan plan) {
        if (!WmsCentralBindings.WAREHOUSE.equals(plan.resourceType())
                || !"ALLOW".equals(plan.decision())) return Set.of();
        if (!Instant.parse(plan.validUntil()).isAfter(Instant.now()))
            throw new CentralAuthorizationException(
                    CentralAuthorizationException.Reason.UNAVAILABLE);
        Set<String> allowed = new LinkedHashSet<>();
        for (var alternative : plan.alternatives()) {
            Set<String> intersection = null;
            for (var clause : alternative.clauses()) {
                if (clause.kind() != ScopeDtos.Kind.SPECIFIED_RESOURCES) {
                    throw new CentralAuthorizationException(
                            CentralAuthorizationException.Reason.UNAVAILABLE);
                }
                if (intersection == null) intersection = new HashSet<>(clause.values());
                else intersection.retainAll(clause.values());
            }
            if (intersection != null) allowed.addAll(intersection);
        }
        return Set.copyOf(allowed);
    }

    record Navigation(
            String mode,
            String enterpriseId,
            String warehouseId,
            List<String> warehouseIds,
            List<String> scopes,
            List<String> capabilities,
            List<NavigationDtos.Menu> menus,
            String observedAt,
            long manifestVersion,
            String state) {}

    /** 按当前仓复核本人能力，菜单来源仍为中央本人接口；提示不能作为后续执行凭据。 */
    Navigation navigation(Jwt jwt, String warehouse) {
        verifyEnterprise(jwt);
        if (warehouse != null && !warehouse.matches("[A-Za-z0-9_:/.-]{1,100}"))
            throw new IllegalArgumentException("仓标识无效");
        var view =
                remote(
                        () ->
                                client.navigation(
                                        jwt.getTokenValue(),
                                        new NavigationDtos.Request(
                                                settings.tenant(),
                                                null,
                                                UUID.randomUUID().toString())));
        if (!bindings.contentHash(view.manifestVersion()).equals(view.contentHash())) {
            // 未同步的权限语义变更不能借旧本地菜单关联展示；纯名称/顺序发布可正常共存。
            throw new CentralAuthorizationException(
                    CentralAuthorizationException.Reason.UNAVAILABLE);
        }
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        Set<String> allowed = new HashSet<>();
        Set<String> scopes = new HashSet<>();
        Set<String> warehouses = new HashSet<>();
        var pending = new ArrayList<java.util.concurrent.Future<CapabilityPlan>>();
        try {
            // 中央菜单提示只覆盖菜单准入能力；写动作可能不在其中，按已核对hash的完整目录逐项复核。
            var capabilities = new LinkedHashSet<String>();
            for (var binding : bindings.all()) {
                String capability = binding.capability();
                if (!capabilities.add(capability)) continue;
                // 显式携带固定用户/成员代际，不传播Servlet线程或把凭据写入持久任务。
                pending.add(
                        navigationChecks.submit(
                                () ->
                                        new CapabilityPlan(
                                                binding,
                                                plan(
                                                        jwt.getTokenValue(),
                                                        capability,
                                                        binding.resource(),
                                                        view.context().membershipGeneration(),
                                                        false))));
            }
            for (var future : pending) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
                var checked = future.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                var binding = checked.binding();
                var plan = checked.plan();
                String capability = binding.capability();
                requireSameIdentity(view.context(), plan.context());
                if (!"ALLOW".equals(plan.decision())) continue;
                Set<String> currentWarehouses = warehouseIds(plan);
                warehouses.addAll(currentWarehouses);
                if (WmsCentralBindings.ENTERPRISE.equals(binding.resource())
                        || warehouse == null
                        || currentWarehouses.contains(warehouse)) {
                    allowed.add(capability);
                    scopes.add(binding.legacy());
                }
            }
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof CentralAuthorizationException rejected)
                throw rejected;
            throw new CentralAuthorizationException(
                    CentralAuthorizationException.Reason.UNAVAILABLE);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CentralAuthorizationException(
                    CentralAuthorizationException.Reason.UNAVAILABLE);
        } catch (java.util.concurrent.TimeoutException
                | java.util.concurrent.RejectedExecutionException unavailable) {
            throw new CentralAuthorizationException(
                    CentralAuthorizationException.Reason.UNAVAILABLE);
        } finally {
            pending.forEach(future -> future.cancel(true));
        }
        var menus = new ArrayList<NavigationDtos.Menu>();
        Set<String> visible = new HashSet<>();
        for (var menu : view.menus()) {
            var required = bindings.requiredForMenu(menu.code());
            if (menu.route() != null && required.stream().anyMatch(allowed::contains)) {
                menus.add(menu);
                visible.add(menu.code());
            }
        }
        // 只保留可见叶子的祖先，空分组不能误显示为可操作入口。
        boolean changed;
        do {
            changed = false;
            for (var menu : view.menus())
                if (visible.contains(menu.code()) && menu.parent() != null)
                    changed |= visible.add(menu.parent());
        } while (changed);
        for (var menu : view.menus())
            if (menu.route() == null && visible.contains(menu.code())) menus.add(menu);
        menus.sort(
                java.util.Comparator.comparingInt(
                        menu -> menu.position() == null ? Integer.MAX_VALUE : menu.position()));
        return new Navigation(
                "CENTRAL",
                settings.enterprise(),
                warehouse,
                warehouses.stream().sorted().toList(),
                scopes.stream().sorted().toList(),
                allowed.stream().sorted().toList(),
                List.copyOf(menus),
                view.observedAt(),
                view.manifestVersion(),
                menus.isEmpty() ? "NO_ACCESS" : "AVAILABLE");
    }

    private <T> T remote(Supplier<T> action) {
        if (!capacity.tryAcquire())
            throw new CentralAuthorizationException(
                    CentralAuthorizationException.Reason.UNAVAILABLE);
        try {
            return action.get();
        } catch (AccessDeniedException rejected) {
            throw new CentralAuthorizationException(CentralAuthorizationException.Reason.DENIED);
        } catch (CentralAccessException failure) {
            throw new CentralAuthorizationException(
                    failure.status() == 401
                            ? CentralAuthorizationException.Reason.UNAUTHENTICATED
                            : CentralAuthorizationException.Reason.UNAVAILABLE);
        } finally {
            capacity.release();
        }
    }

    private record CapabilityPlan(WmsCentralBindings.Binding binding, Plan plan) {}

    /** 多次范围读取必须仍属于同一主体/成员快照；追踪nonce不同不构成身份变化。 */
    static void requireSameIdentity(
            com.lrj.authz.protocol.GovernanceDtos.AccessContext first,
            com.lrj.authz.protocol.GovernanceDtos.AccessContext next) {
        if (!first.principalId().equals(next.principalId())
                || !first.membershipId().equals(next.membershipId())
                || first.membershipGeneration() != next.membershipGeneration()
                || first.membershipVersion() != next.membershipVersion()
                || first.principalVersion() != next.principalVersion()
                || !first.tenantId().equals(next.tenantId())
                || !first.applicationId().equals(next.applicationId())
                || !first.environment().equals(next.environment())
                || !first.callerServiceId().equals(next.callerServiceId())) {
            throw new CentralAuthorizationException(
                    CentralAuthorizationException.Reason.UNAVAILABLE);
        }
    }

    /** 关闭仅终止本实例的提示任务，不影响共享线程池或其他应用。 */
    @Override
    public void close() {
        navigationChecks.shutdownNow();
    }
}
