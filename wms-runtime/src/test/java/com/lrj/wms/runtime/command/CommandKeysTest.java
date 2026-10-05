package com.lrj.wms.runtime.command;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 请求头与正文不能分别指向两个命令，且不裁剪合法键造成身份碰撞。 */
class CommandKeysTest {
    @Test
    void headerIsAuthoritativeAndMismatchIsRejected() {
        assertEquals("KEY", CommandKeys.resolve("KEY", null));
        assertEquals("KEY", CommandKeys.resolve("KEY", "KEY"));
        assertThrows(InvalidCommandKeyException.class, () -> CommandKeys.resolve(null, "KEY"));
        assertThrows(InvalidCommandKeyException.class, () -> CommandKeys.resolve("KEY", "ANOTHER"));
        assertThrows(
                InvalidCommandKeyException.class, () -> CommandKeys.resolve("x".repeat(65), null));
    }
}
