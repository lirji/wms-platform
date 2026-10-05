package com.lrj.wms.driver.health;

public record HealthCheckItem(String name, boolean passed, String message) {}
