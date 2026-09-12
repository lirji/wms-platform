package com.lrj.wms.inventory;

import com.lrj.wms.inventory.messaging.InventoryCellRouting;
import com.lrj.wms.runtime.messaging.*;
import java.util.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 路由配置及投递筛选边界；业务效果另由真实双cell进程验证。 */
class InventoryCellRoutingTest {
    @Test void explicitCellsGetStableSeparateGroupsAndChangedRoutesReplayWithNewGroup() {
        var first=new InventoryCellRouting("A",routing(false,1),true);
        assertEquals(first.group("wms.test"),new InventoryCellRouting("A",routing(true,1),true).group("wms.test"));
        assertNotEquals(first.group("wms.test"),new InventoryCellRouting("B",routing(false,1),true).group("wms.test"));
        assertNotEquals(first.group("wms.test"),new InventoryCellRouting("A",routing(false,2),true).group("wms.test"));
        assertEquals("wms.test.inventory-projection",new InventoryCellRouting(null,null,false).group("wms.test"));
        assertThrows(IllegalArgumentException.class,()->new InventoryCellRouting("A",null,true));
        assertThrows(IllegalArgumentException.class,()->new InventoryCellRouting(null,routing(false,1),true));
        assertThrows(IllegalArgumentException.class,()->new InventoryCellRouting("A",routing(false,1).replace("\"schemaVersion\":1","\"schemaVersion\":4294967297"),true));
    }
    @Test void onlyExplicitForeignWarehouseCanBeSkippedUnknownAndMalformedStayDurable() {
        var inbox=mock(RuntimeInbox.class);var receiver=new InventoryCellRouting("A",routing(false,1),true).receiver(inbox);
        var own=record("A",0);var foreign=record("B",1);var unknown=record("C",2);
        var malformed=new ConsumerRecord<String,String>("wms.test.inbound.commands",0,3,"key","{}");
        receiver.persist(own);receiver.persist(foreign);receiver.persist(unknown);receiver.persist(malformed);
        verify(inbox).persist(own);verify(inbox,never()).persist(foreign);verify(inbox).persist(unknown);verify(inbox).persist(malformed);
    }
    private static ConsumerRecord<String,String> record(String wh,int offset) {
        return new ConsumerRecord<>("wms.test.inbound.commands",0,offset,"key",new RuntimeMessage(1,"EVENT-"+wh,"wms-inbound","ENT",wh,"StockCommandRequested","EFFECT",1,"2026-09-13T00:00:00Z",null,RuntimeMessage.JSON.createObjectNode()).encode());
    }
    private static String routing(boolean reverse,int epoch) {
        var a=Map.of("enterpriseId","ENT","warehouseId","A","cellId","A","routeEpoch",epoch);
        var b=Map.of("enterpriseId","ENT","warehouseId","B","cellId","B","routeEpoch",1);
        return RuntimeMessage.JSON.writeValueAsString(Map.of("schemaVersion",1,"routes",reverse?List.of(b,a):List.of(a,b)));
    }
}
