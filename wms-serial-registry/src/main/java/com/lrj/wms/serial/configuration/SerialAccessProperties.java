package com.lrj.wms.serial.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 内部登记只接受显式配置的签名服务主体，作业scope仍须单独校验。 */
@ConfigurationProperties("wms.serial.access")
public record SerialAccessProperties(List<String> allowedSubjects) {
    /** 在不可变契约的构造边界统一处理输入，保证默认值、校验与字段复制不在各调用点分叉。 */
    public SerialAccessProperties {
        allowedSubjects = allowedSubjects == null ? List.of() : List.copyOf(allowedSubjects);
        if (allowedSubjects.size() > 20
                || allowedSubjects.stream()
                        .anyMatch(subject -> subject.isBlank() || subject.length() > 64)) {
            throw new IllegalArgumentException("序列号登记服务主体配置无效");
        }
    }
}
