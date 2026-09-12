package com.lrj.wms.inventory.count;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 盘点逐身份恢复只访问原企业仓/计划；原业务输入不可更新。 */
public interface CountSerialMapper {
    /** 同一原行只保留首个调整上下文，不用重试覆盖原操作。 */
    int insertAdjustment(@Param("row") Map<String,Object> row);
    /** 按原行加锁，HTTP和后台共同复用首个调整意图。 */
    Map<String,Object> lockAdjustment(@Param("e") String e,@Param("w") String w,@Param("line") String line);
    /** 只取到期且尚无意图的有界行集合，避免失败行阻塞后续计划。 */
    List<String> unstagedLines(@Param("e") String e,@Param("w") String w,@Param("plan") String plan,@Param("now") Timestamp now);
    /** 身份子意图与父快照同事务，任何插入失败必须回滚全部准备。 */
    int insertSerial(@Param("row") Map<String,Object> row);
    /** 按身份稳定顺序读取持久凭证，调用方检查全部DONE后再应用。 */
    List<Map<String,Object>> serials(@Param("e") String e,@Param("w") String w,@Param("adjustment") String adjustment);
    /** 仅在所有子意图完成时推进有界父意图，不等于库存已调整。 */
    int ready(@Param("e") String e,@Param("w") String w,@Param("plan") String plan,@Param("now") Timestamp now);
    /** 与库存和身份同时提交屏障，影响行数用于检测重复或竞争。 */
    int applied(@Param("e") String e,@Param("w") String w,@Param("line") String line,@Param("now") Timestamp now);
    /** 跳过其他执行器已锁行，租约到期才允许重新领取。 */
    Map<String,Object> next(@Param("e") String e,@Param("w") String w,@Param("plan") String plan,@Param("now") Timestamp now);
    /** 增加领取代际和次数，旧执行器不能覆盖接管者。 */
    int claim(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("epoch") long epoch,@Param("until") Timestamp until,@Param("now") Timestamp now);
    /** 仅原领取代际可保存结果，失败结果不能伪装成DONE。 */
    int finish(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("epoch") long epoch,@Param("state") String state,@Param("result") String result,@Param("error") String error,@Param("next") Timestamp next,@Param("now") Timestamp now);
    /** 审计重排前锁定同企业仓原身份意图。 */
    Map<String,Object> lock(@Param("e") String e,@Param("w") String w,@Param("id") String id);
    /** 仅PENDING父意图下的当前隔离代际可重排，原输入保持不变。 */
    int requeue(@Param("e") String e,@Param("w") String w,@Param("id") String id,@Param("epoch") long epoch,@Param("now") Timestamp now);
    /** 正常等待原收货登记不消耗整行失败预算；其他前置失败有界隔离。 */
    int deferStage(@Param("e") String e,@Param("w") String w,@Param("line") String line,@Param("waiting") boolean waiting,@Param("error") String error,@Param("next") Timestamp next);

}
