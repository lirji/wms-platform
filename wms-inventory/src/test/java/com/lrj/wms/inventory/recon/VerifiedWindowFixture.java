package com.lrj.wms.inventory.recon;

import java.sql.Timestamp;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 仅供既有对账/导出下游测试建立已核验前置；不冒充来源HTTP或库存采集器验收。 */
final class VerifiedWindowFixture {
    private VerifiedWindowFixture() { }
    static void seed(SqlSession session,String e,String w,String id,Timestamp cutoff,String source,String posting,String receipt) {
        try(var statement=session.getConnection().prepareStatement("INSERT INTO reconciliation_cutoff(id,enterprise_id,warehouse_id,cutoff_id,closed_at,source_watermark,posting_watermark,receipt_watermark,watermarks_complete,evidence_version,state,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,1,1,'CLOSED',0,?,?) ON DUPLICATE KEY UPDATE watermarks_complete=1,evidence_version=1")) {
            Object[] args={UUID.randomUUID().toString(),e,w,id,cutoff,source,posting,receipt,cutoff,cutoff};
            for(int n=0;n<args.length;n++) statement.setObject(n+1,args[n]);statement.executeUpdate();
        } catch(java.sql.SQLException failure) {throw new IllegalStateException(failure);}
        try(var statement=session.getConnection().prepareStatement("INSERT INTO reconciliation_history_guard(id,enterprise_id,warehouse_id,closed_before) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE closed_before=GREATEST(COALESCE(closed_before,VALUES(closed_before)),VALUES(closed_before))")) {
            statement.setString(1,UUID.randomUUID().toString());statement.setString(2,e);statement.setString(3,w);statement.setTimestamp(4,cutoff);statement.executeUpdate();
        } catch(java.sql.SQLException failure) {throw new IllegalStateException(failure);}
        session.clearCache();
    }
}
