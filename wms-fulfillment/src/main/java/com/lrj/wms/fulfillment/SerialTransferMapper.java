package com.lrj.wms.fulfillment;

import java.sql.Timestamp;
import java.util.*;
import org.apache.ibatis.annotations.Param;
import com.lrj.wms.contract.messaging.SerialExecutionSelection;

/** 单据原命令和逐SN关联，所有写入由原调拨单锁串行化。 */
public interface SerialTransferMapper {
    Map<String,Object> get(@Param("e") String e,@Param("id") String id);
    Map<String,Object> lock(@Param("e") String e,@Param("id") String id);
    Map<String,Object> authorizationCommand(@Param("e") String e,@Param("authorization") String authorization);
    int insert(@Param("row") Map<String,Object> row);
    int enable(@Param("e") String e,@Param("transfer") String transfer,@Param("line") String line);
    long pendingIssue(@Param("e") String e,@Param("transfer") String transfer,@Param("line") String line);
    int members(@Param("e") String e,@Param("transfer") String transfer,@Param("line") String line,@Param("id") String id,
            @Param("identities") List<SerialExecutionSelection.Identity> identities,@Param("now") Timestamp now);
    List<Map<String,Object>> lockMembers(@Param("e") String e,@Param("transfer") String transfer,@Param("serials") List<String> serials);
    int assign(@Param("e") String e,@Param("transfer") String transfer,@Param("id") String id,@Param("serials") List<String> serials);
    int complete(@Param("e") String e,@Param("id") String id,@Param("now") Timestamp now);
}
