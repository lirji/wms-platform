-- 原订单行和库存桶共同决定预占归属，覆盖有界分批消费查询。
ALTER TABLE reservation_line ADD INDEX idx_reservation_outbound
    (enterprise_id, warehouse_id, reservation_id, order_line_id, balance_id, remaining_qty, id);
