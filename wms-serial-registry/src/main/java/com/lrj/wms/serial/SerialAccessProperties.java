package com.lrj.wms.serial;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 内部登记只接受显式配置的签名服务主体，作业scope仍须单独校验。 */
@ConfigurationProperties("wms.serial.access")
public record SerialAccessProperties(List<String> allowedSubjects) {
    public SerialAccessProperties {
        allowedSubjects = allowedSubjects == null ? List.of() : List.copyOf(allowedSubjects);
        if (allowedSubjects.size() > 20 || allowedSubjects.stream().anyMatch(subject -> subject.isBlank() || subject.length() > 64)) {
            throw new IllegalArgumentException("序列号登记服务主体配置无效");
        }
    }
}
