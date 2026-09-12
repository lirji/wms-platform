package com.lrj.wms.inventory.messaging;

import com.lrj.wms.inventory.migrate.WarehouseRouteMapper;
import com.lrj.wms.runtime.messaging.*;
import java.util.*;
import org.apache.ibatis.session.SqlSession;

/** 多物理cell独立消费后按明确路由清单筛选，业务事务仍锁定数据库权威路由校验代际。 */
public final class InventoryCellRouting {
    private record Scope(String enterprise,String warehouse) { }
    private record Route(String enterpriseId,String warehouseId,String cellId,long routeEpoch) { }
    private final String cell;
    private final Map<Scope,Route> routes;
    private final String generation;

    /** 单库兼容模式不猜测cell；原生RM搭配消息时必须提供完整清单。 */
    public InventoryCellRouting(String cell,String json,boolean required) {
        if(cell==null||cell.isBlank()) {
            if(required||json!=null&&!json.isBlank()) throw new IllegalArgumentException("多cell消息必须明确本cell及路由清单");
            this.cell=null;this.routes=Map.of();this.generation=null;return;
        }
        if(!cell.matches("[A-Za-z0-9_.-]{1,64}")||json==null||json.isBlank()||json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)
            throw new IllegalArgumentException("cell标识和有界路由清单必须明确");
        var node=RuntimeMessage.JSON.readTree(json);
        if(!node.isObject()||node.properties().stream().anyMatch(e->!Set.of("schemaVersion","routes").contains(e.getKey()))
                ||!node.path("schemaVersion").isIntegralNumber()||!node.path("schemaVersion").canConvertToInt()||node.path("schemaVersion").asInt()!=1
                ||!node.path("routes").isArray()||node.path("routes").isEmpty()||node.path("routes").size()>1000)
            throw new IllegalArgumentException("路由清单须为V1且最多1000个企业仓");
        var configured=new HashMap<Scope,Route>();
        for(var entry:node.path("routes")) {
            if(!entry.isObject()||entry.properties().stream().anyMatch(e->!Set.of("enterpriseId","warehouseId","cellId","routeEpoch").contains(e.getKey())))
                throw new IllegalArgumentException("路由字段无效");
            for(String key:List.of("enterpriseId","warehouseId","cellId")) if(!entry.path(key).isString()||entry.path(key).asString().isBlank()||entry.path(key).asString().length()>64)
                throw new IllegalArgumentException("路由身份无效");
            if(!entry.path("cellId").asString().matches("[A-Za-z0-9_.-]{1,64}")||!entry.path("routeEpoch").isIntegralNumber()
                    ||!entry.path("routeEpoch").canConvertToLong()||entry.path("routeEpoch").asLong()<1) throw new IllegalArgumentException("路由代际无效");
            Route route=new Route(entry.path("enterpriseId").asString(),entry.path("warehouseId").asString(),entry.path("cellId").asString(),entry.path("routeEpoch").asLong());
            if(configured.put(new Scope(route.enterpriseId(),route.warehouseId()),route)!=null) throw new IllegalArgumentException("同一企业仓不能配置多个归属");
        }
        if(configured.values().stream().noneMatch(r->cell.equals(r.cellId()))) throw new IllegalArgumentException("路由清单未包含本cell的仓");
        this.cell=cell;this.routes=Map.copyOf(configured);
        var canonical=configured.values().stream().sorted(Comparator.comparing(Route::enterpriseId).thenComparing(Route::warehouseId)).toList();
        this.generation=RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(List.of("inventory-cell-v1",cell,canonical)));
    }

    /** 配置内容改变使用新组从保留日志重放；原Inbox业务身份去重，不复用曾跳过该仓的位点。 */
    public String group(String prefix) {return prefix+(cell==null?".inventory-projection":".inventory-cell."+generation);}

    /** 明确属于其他cell才跳过；未知仓和非法信封仍持久化，不能悄悄吞掉错误配置或毒消息。 */
    public KafkaInboxConsumer.DurableReceiver receiver(RuntimeInbox inbox) {
        return record->{
            if(cell!=null) {
                RuntimeMessage message;
                try {message=RuntimeMessage.parse(record.value());}
                catch(MessageRejectedException malformed) {inbox.persist(record);return;}
                var route=routes.get(new Scope(message.enterpriseId(),message.warehouseId()));
                if(route!=null&&!cell.equals(route.cellId())) return;
            }
            inbox.persist(record);
        };
    }

    /** 迁移或配置陈旧时拒绝本地处理；路由锁与库存效果在同一个事务内，不能先检查后跨事务写。 */
    public void requireLocal(SqlSession session,RuntimeMessage message) {
        if(cell==null) return;
        var route=routes.get(new Scope(message.enterpriseId(),message.warehouseId()));
        if(route==null) throw new MessageRejectedException("CELL_ROUTE_UNREGISTERED");
        if(!cell.equals(route.cellId())) throw new MessageRejectedException("CELL_ROUTE_FOREIGN");
        var current=session.getMapper(WarehouseRouteMapper.class).lock(message.enterpriseId(),message.warehouseId());
        if(current==null||!cell.equals(current.get("cell_id"))||!"ACTIVE".equals(current.get("state"))
                ||!(current.get("route_epoch") instanceof Number epoch)||epoch.longValue()!=route.routeEpoch())
            throw new MessageRejectedException("CELL_ROUTE_CHANGED");
    }
}
