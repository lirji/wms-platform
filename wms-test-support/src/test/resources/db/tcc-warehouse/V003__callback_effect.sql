CREATE TABLE callback_effect (
 xid VARCHAR(128) NOT NULL COMMENT '全局事务标识',
 branch_id BIGINT NOT NULL COMMENT '分支标识',
 phase VARCHAR(16) NOT NULL COMMENT '探针二阶段CONFIRM或CANCEL',
 PRIMARY KEY (xid,branch_id,phase)
) ENGINE=InnoDB COMMENT='真实TC回调效果探针，验证与Fence同事务';
