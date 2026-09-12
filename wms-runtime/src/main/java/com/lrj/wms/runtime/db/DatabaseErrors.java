package com.lrj.wms.runtime.db;

import java.sql.SQLException;

/** 根据驱动结构化错误分类，不能用所有运行时异常或 SQLState 23000 冒充重复键。 */
public final class DatabaseErrors {
    private DatabaseErrors() { }
    public static boolean duplicateKey(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && sql.getErrorCode() == 1062) return true;
        }
        return false;
    }
}
