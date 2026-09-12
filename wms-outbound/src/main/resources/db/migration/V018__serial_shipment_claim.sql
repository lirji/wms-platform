ALTER TABLE outbound_serial_pick
 ADD COLUMN shipment_command_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '原发运命令，非空表示身份发运额度已占用',
 ADD COLUMN shipment_posted_at DATETIME(6) NULL COMMENT '原库存发运回执UTC时刻，不等于全局登记已完成',
 ADD KEY idx_serial_shippable(enterprise_id,warehouse_id,order_id,order_line_id,target_location_id,lot_id,state,serial_id),
 ADD CONSTRAINT ck_serial_shipment_receipt CHECK(shipment_posted_at IS NULL OR shipment_command_id IS NOT NULL);
