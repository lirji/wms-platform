# 在容器内用仓库锁定的 JDK/Maven 编译可运行进程；不把源码挂进运行镜像。
ARG JDK_IMAGE=eclipse-temurin:21.0.8_9-jdk-jammy
ARG JRE_IMAGE=eclipse-temurin:21.0.8_9-jre-jammy
# 与CI一致，从不可变源码及下载摘要构建SDK；不复制宿主机Maven产物。
FROM ${JDK_IMAGE} AS auth-sdk
WORKDIR /auth-src
ADD --checksum=sha256:dfc575fbd26d2b1b5435fc08beea0f6bdfca8c915f78318035343c294c79fa57 https://codeload.github.com/lirji/auth-platform/tar.gz/7712d2606805afb3b9a7de7a6d88fe94a28104fb /auth-source.tar.gz
RUN tar -xzf /auth-source.tar.gz --strip-components=1 -C /auth-src \
    && ./mvnw -B -ntp -pl auth-platform-sdk -am install -DskipTests \
    && mkdir -p /auth-maven \
    && cp -a /root/.m2/repository/com/lrj/authz /auth-maven/authz

FROM ${JDK_IMAGE} AS build
WORKDIR /src
COPY --from=auth-sdk /auth-maven /auth-maven
ENV MAVEN_OPTS="-Xmx2g"
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY wms-contract/pom.xml wms-contract/pom.xml
COPY wms-runtime/pom.xml wms-runtime/pom.xml
COPY wms-security/pom.xml wms-security/pom.xml
COPY wms-inbound/pom.xml wms-inbound/pom.xml
COPY wms-outbound/pom.xml wms-outbound/pom.xml
COPY wms-inventory/pom.xml wms-inventory/pom.xml
COPY wms-serial-registry/pom.xml wms-serial-registry/pom.xml
COPY wms-fulfillment/pom.xml wms-fulfillment/pom.xml
COPY wms-integration/pom.xml wms-integration/pom.xml
COPY wms-test-support/pom.xml wms-test-support/pom.xml
RUN chmod +x mvnw
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mkdir -p /root/.m2/repository/com/lrj \
    && cp -a /auth-maven/authz /root/.m2/repository/com/lrj/ \
    && ./mvnw -B -ntp -Dmaven.test.skip=true package \
      -pl wms-inbound,wms-outbound,wms-inventory,wms-serial-registry,wms-fulfillment \
      -am

FROM ${JRE_IMAGE} AS runtime
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home wms
COPY --from=build /src/wms-inbound/target/wms-inbound-0.1.0-SNAPSHOT.jar /app/wms-inbound.jar
COPY --from=build /src/wms-outbound/target/wms-outbound-0.1.0-SNAPSHOT.jar /app/wms-outbound.jar
COPY --from=build /src/wms-inventory/target/wms-inventory-0.1.0-SNAPSHOT.jar /app/wms-inventory.jar
COPY --from=build /src/wms-serial-registry/target/wms-serial-registry-0.1.0-SNAPSHOT.jar /app/wms-serial-registry.jar
COPY --from=build /src/wms-fulfillment/target/wms-fulfillment-0.1.0-SNAPSHOT.jar /app/wms-fulfillment.jar
COPY deploy/seata/file.conf deploy/seata/registry.conf /app/
RUN chown -R wms:wms /app
USER wms
ENV WMS_BIND_ADDRESS=0.0.0.0
EXPOSE 18181 18182 18183 18184 18185
ENTRYPOINT ["java", "-jar"]
CMD ["/app/wms-inbound.jar"]
