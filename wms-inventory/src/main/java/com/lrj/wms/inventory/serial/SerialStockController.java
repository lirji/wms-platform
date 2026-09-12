package com.lrj.wms.inventory.serial;

import com.lrj.wms.inventory.OnInventoryJdbcConfigured;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.runtime.web.CursorPage;
import com.lrj.wms.security.WmsJwtAuthorities;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 查询当前可选择的授权身份；选择本身不预占，PICK事务仍核对原订单与代际。 */
@RestController
@Conditional(OnInventoryJdbcConfigured.class)
@RequestMapping("/api/wms/v1/warehouses/{warehouseId}/serial-stock")
public final class SerialStockController {
    private final SqlSessionFactory sessions;
    public SerialStockController(SqlSessionFactory sessions) {this.sessions=sessions;}

    /** 游标绑定完整桶与企业仓，已拣或设备领取中的身份不能作为新PICK选择返回。 */
    @GetMapping
    public Map<String,Object> list(@AuthenticationPrincipal Jwt jwt,@PathVariable String warehouseId,
            @RequestParam String ownerId,@RequestParam String skuId,@RequestParam String locationId,
            @RequestParam(defaultValue="NO_LOT") String lotId,@RequestParam(required=false) Integer limit,@RequestParam(required=false) String cursor) {
        WmsJwtAuthorities.requireWarehouse(jwt,warehouseId);WmsJwtAuthorities.requireScope(jwt,"inventory.read");
        for(String value:new String[]{ownerId,skuId,locationId,lotId})
            if(value.isBlank() || value.length()>64) throw new InventoryException("INVALID_ARGUMENT","序列库存查询需要合法的完整库存桶");
        String e=WmsJwtAuthorities.enterpriseId(jwt);
        var page=CursorPage.parse(limit,cursor,CursorPage.scope("serial-stock",e,warehouseId,ownerId,skuId,locationId,lotId));
        try(var session=sessions.openSession()) {
            var rows=session.getMapper(SerialOutboundMapper.class).available(e,warehouseId,ownerId,skuId,locationId,lotId,page);
            var result=page.result(rows,false);rows.forEach(row -> row.remove("id"));return result;
        }
    }
    @ExceptionHandler({com.lrj.wms.security.WarehouseForbiddenException.class,com.lrj.wms.security.ScopeForbiddenException.class})
    org.springframework.http.ResponseEntity<Map<String,Object>> forbidden() {
        return error(403,"SERIAL_STOCK_FORBIDDEN","缺少库存查询权限或仓范围");
    }
    @ExceptionHandler({InventoryException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
    org.springframework.http.ResponseEntity<Map<String,Object>> invalid() {return error(400,"INVALID_ARGUMENT","序列库存查询需要合法的完整库存桶");}
    private static org.springframework.http.ResponseEntity<Map<String,Object>> error(int status,String code,String message) {
        return org.springframework.http.ResponseEntity.status(status).body(Map.of("code",code,"message",message,"retryable",false,
                "requestId",com.lrj.wms.runtime.observability.RequestCorrelationFilter.currentId()));
    }
}
