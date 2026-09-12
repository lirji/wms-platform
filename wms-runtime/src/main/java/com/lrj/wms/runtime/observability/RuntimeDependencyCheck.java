package com.lrj.wms.runtime.observability;

/** 已启用业务链路提供自己的依赖探针，统一参与接流量判定。 */
public interface RuntimeDependencyCheck extends org.springframework.boot.health.contributor.HealthIndicator { }
