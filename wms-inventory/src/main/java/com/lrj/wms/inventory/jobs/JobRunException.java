package com.lrj.wms.inventory.jobs;

/** 任务运行冲突，携带稳定错误码。 */
public final class JobRunException extends RuntimeException {
    private final String code;

    public JobRunException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
