package com.lrj.wms.runtime.cache;

/** 回源预算耗尽是临时过载，必须返回可重试错误，不能向数据库无限放行。 */
public final class QueryCacheBusyException extends RuntimeException {
    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public QueryCacheBusyException() {
        super("查询回源预算已用尽");
    }
}
