package com.lrj.wms.fulfillment;

/** TM外部副作用边界，数据库事务不得跨过调用；本地记录的是请求意图而非全局决策。 */
public interface AllocationTmPort {
    /** begin不可重试；返回丢失时保留未知启动。 */
    String begin(String attemptId, int timeoutMillis);
    /** 原XID提交请求，不代表已经取得持久化终态。 */
    void commit(String xid);
    /** 原XID回滚请求，二阶段继续由TC调度。 */
    void rollback(String xid);
}
