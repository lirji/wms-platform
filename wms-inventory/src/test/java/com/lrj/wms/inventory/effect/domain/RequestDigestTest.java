package com.lrj.wms.inventory.effect.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** digestVersion 重放必须用原规范；适配层不得随机生成身份。 */
class RequestDigestTest {
    @Test
    void versionTwoDoesNotChangeStoredVersionOne() {
        String v1 = RequestDigest.digest(RequestDigest.VERSION_1, EffectCodes.ACTION_RECEIVE, EffectCodes.FACT_RECEIPT_PART,
                "SES-1", "PART-1", "LINE-1", null, null);
        String v2 = RequestDigest.digest(RequestDigest.VERSION_2, EffectCodes.ACTION_RECEIVE, EffectCodes.FACT_RECEIPT_PART,
                "SES-1", "PART-1", "LINE-1", "12", "EA");
        assertNotEquals(v1, v2);
        String replay = RequestDigest.digest(RequestDigest.VERSION_1, EffectCodes.ACTION_RECEIVE,
                EffectCodes.FACT_RECEIPT_PART, "SES-1", "PART-1", "LINE-1", "12", "EA");
        assertEquals(v1, replay);
    }

    @Test
    void unknownVersionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RequestDigest.digest(9, EffectCodes.ACTION_RECEIVE,
                EffectCodes.FACT_RECEIPT_PART, "P", "A", "L", null, null));
    }

    @Test
    void legacyAdapterRefusesIncompleteFacts() {
        assertThrows(EffectProtocolException.class,
                () -> LegacyIdentityAdapter.requireRecoverableFacts(EffectCodes.ACTION_RECEIVE, null, "P", "A", "L"));
        LegacyIdentityAdapter.FactKey key = LegacyIdentityAdapter.requireRecoverableFacts(EffectCodes.ACTION_RECEIVE,
                EffectCodes.FACT_RECEIPT_PART, "P", "A", "L");
        assertEquals(EffectCodes.ACTION_RECEIVE, key.action());
    }

    @Test
    void unknownActionDoesNotFallback() {
        assertThrows(IllegalArgumentException.class, () -> EffectCodes.requireAction("POST"));
        assertThrows(IllegalArgumentException.class, () -> EffectCodes.requireEffectState("SUCCESS"));
        assertTrue(EffectCodes.blocksNewAttempt(EffectCodes.STATE_STARTED));
        assertTrue(EffectCodes.allowsNewAttempt(EffectCodes.STATE_SAFE_CLOSED));
        assertFalse(EffectCodes.allowsNewAttempt(EffectCodes.STATE_APPLIED));
    }
}
