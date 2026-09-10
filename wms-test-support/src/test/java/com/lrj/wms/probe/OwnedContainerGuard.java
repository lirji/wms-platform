package com.lrj.wms.probe;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故障注入只允许本测试登记过的容器；拒绝按名字误杀共享 dev-infra。
 * 共享容器自己的健康重启不记为本测试破坏；本测试操作集合必须与 owned 完全一致。
 */
final class OwnedContainerGuard {
    private final DockerClient docker;
    private final Set<String> shared = new LinkedHashSet<>();
    private final Set<String> owned = new LinkedHashSet<>();
    private final List<String> mutations = new ArrayList<>();

    private OwnedContainerGuard(DockerClient docker) {
        this.docker = docker;
    }

    /** 快照名称或 Compose 项目含 dev-infra 的容器，禁止登记或 kill/start。 */
    static OwnedContainerGuard snapshotShared(DockerClient docker) {
        var guard = new OwnedContainerGuard(docker);
        for (Container container : docker.listContainersCmd().withShowAll(true).exec()) {
            if (shared(container)) {
                guard.shared.add(container.getId());
            }
        }
        return guard;
    }

    void registerOwned(String... ids) {
        for (String id : ids) {
            assertFalse(id == null || id.isBlank(), "本任务容器ID不能为空");
            assertFalse(shared.contains(id), "禁止把共享dev-infra登记为本任务容器");
            owned.add(id);
        }
    }

    /** 非本任务ID以及共享基础设施一律拒绝，即使调用方写了真实容器名。 */
    void refuseForeignAndShared() {
        assertThrows(IllegalStateException.class, () -> kill("not-owned-failure-it"));
        for (String sharedId : shared) {
            assertThrows(IllegalStateException.class, () -> kill(sharedId));
        }
    }

    void kill(String containerId) {
        requireOwned(containerId);
        mutations.add("kill:" + containerId);
        docker.killContainerCmd(containerId).exec();
    }

    void start(String containerId) {
        requireOwned(containerId);
        mutations.add("start:" + containerId);
        docker.startContainerCmd(containerId).exec();
    }

    void assertSharedUntouched() {
        for (String sharedId : shared) {
            docker.inspectContainerCmd(sharedId).exec();
        }
        for (String mutation : mutations) {
            String id = mutation.substring(mutation.indexOf(':') + 1);
            assertTrue(owned.contains(id), "故障操作必须落在本任务容器: " + mutation);
            assertFalse(shared.contains(id), "故障操作命中共享dev-infra: " + mutation);
        }
    }

    private void requireOwned(String containerId) {
        if (containerId == null || !owned.contains(containerId) || shared.contains(containerId)) {
            throw new IllegalStateException("拒绝操作非本任务或共享dev-infra容器: " + containerId);
        }
    }

    private static boolean shared(Container container) {
        String names = container.getNames() == null ? "" : String.join(",", container.getNames());
        String project = container.getLabels() == null ? ""
                : container.getLabels().getOrDefault("com.docker.compose.project", "");
        String haystack = (names + " " + project).toLowerCase(Locale.ROOT);
        return haystack.contains("dev-infra") || haystack.contains("dev_infra");
    }
}
