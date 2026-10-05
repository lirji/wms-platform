package com.lrj.wms.runtime.command;

/** HTTP 命令键只有一个权威值，正文仅可重复该值以便客户端恢复。 */
public final class CommandKeys {
    private CommandKeys() {}

    /** 合并头与请求体中的操作键，冲突时拒绝，重试不能改用另一操作身份。 */
    public static String resolve(String header, String body) {
        if (header == null
                || header.isBlank()
                || header.length() > 64
                || body != null && !body.isBlank() && !header.equals(body)) {
            throw new InvalidCommandKeyException();
        }
        return header;
    }
}
