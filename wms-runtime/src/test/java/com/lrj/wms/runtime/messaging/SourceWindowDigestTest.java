package com.lrj.wms.runtime.messaging;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 跨语言固定向量由独立JSON/SHA256计算，防止序列化器升级静默改变已保存证明。 */
class SourceWindowDigestTest {
    @Test void versionOneDigestHasStableCrossLanguageVector() {
        String initial=SourceWindowService.initialDigest("wms-outbound","ENT","WH","CUT",Instant.parse("2026-09-13T00:00:00Z"));
        assertEquals("2bc0830ea40fa38e41cf41cb7782a4af3706c39f4f6c5257fe3d9c7263870da0",initial);
        assertEquals("79bc50edbef9e33bb0c3ab2f31b32da17d17f34c56e53e7500d8403f20c0047b",SourceWindowService.append(initial,
                new SourceWindowService.Fact("CMD","SHIP","EX","1","1","POST","2026-09-12T00:00:00Z","APPLIED")));
    }
}
