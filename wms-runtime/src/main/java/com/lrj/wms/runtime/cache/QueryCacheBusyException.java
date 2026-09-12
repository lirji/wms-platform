package com.lrj.wms.runtime.cache;

/** 回源预算耗尽是临时过载，必须返回可重试错误，不能向数据库无限放行。 */
public final class QueryCacheBusyException extends RuntimeException {
    public QueryCacheBusyException() { super("查询回源预算已用尽"); }
}
