--
-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file distributed with
-- this work for additional information regarding copyright ownership.
-- The ASF licenses this file to You under the Apache License, Version 2.0
-- (the "License"); you may not use this file except in compliance with
-- the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

-- -------------------------------- The script used when storeMode is 'db' --------------------------------
-- the table to store GlobalSession data
CREATE TABLE IF NOT EXISTS `global_table`
(
    `xid`                       VARCHAR(128) NOT NULL COMMENT '全局事务标识',
    `transaction_id`            BIGINT COMMENT '全局事务数值标识',
    `status`                    TINYINT      NOT NULL COMMENT 'Seata状态码',
    `application_id`            VARCHAR(32) COMMENT '发起应用',
    `transaction_service_group` VARCHAR(32) COMMENT '事务分组',
    `transaction_name`          VARCHAR(128) COMMENT '事务名称',
    `timeout`                   INT COMMENT '事务超时毫秒',
    `begin_time`                BIGINT COMMENT '开始时间毫秒',
    `application_data`          VARCHAR(2000) COMMENT '协议上下文',
    `gmt_create`                DATETIME COMMENT '创建时间',
    `gmt_modified`              DATETIME COMMENT '修改时间',
    PRIMARY KEY (`xid`),
    KEY `idx_status_gmt_modified` (`status` , `gmt_modified`),
    KEY `idx_transaction_id` (`transaction_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = 'Seata 2.6.0 隔离探针内部表';

-- the table to store BranchSession data
CREATE TABLE IF NOT EXISTS `branch_table`
(
    `branch_id`         BIGINT       NOT NULL COMMENT '分支标识',
    `xid`               VARCHAR(128) NOT NULL COMMENT '全局事务标识',
    `transaction_id`    BIGINT COMMENT '全局事务数值标识',
    `resource_group_id` VARCHAR(32) COMMENT '资源分组',
    `resource_id`       VARCHAR(256) COMMENT '资源标识',
    `branch_type`       VARCHAR(8) COMMENT '分支类型',
    `status`            TINYINT COMMENT 'Seata状态码',
    `client_id`         VARCHAR(64) COMMENT '客户端标识',
    `application_data`  VARCHAR(2000) COMMENT '协议上下文',
    `gmt_create`        DATETIME(6) COMMENT '创建时间',
    `gmt_modified`      DATETIME(6) COMMENT '修改时间',
    PRIMARY KEY (`branch_id`),
    KEY `idx_xid` (`xid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = 'Seata 2.6.0 隔离探针内部表';

-- the table to store lock data
CREATE TABLE IF NOT EXISTS `lock_table`
(
    `row_key`        VARCHAR(128) NOT NULL COMMENT '锁行键',
    `xid`            VARCHAR(128) COMMENT '全局事务标识',
    `transaction_id` BIGINT COMMENT '全局事务数值标识',
    `branch_id`      BIGINT       NOT NULL COMMENT '分支标识',
    `resource_id`    VARCHAR(256) COMMENT '资源标识',
    `table_name`     VARCHAR(32) COMMENT '业务表名',
    `pk`             VARCHAR(36) COMMENT '业务主键',
    `status`         TINYINT      NOT NULL DEFAULT '0' COMMENT '0:locked ,1:rollbacking',
    `gmt_create`     DATETIME COMMENT '创建时间',
    `gmt_modified`   DATETIME COMMENT '修改时间',
    PRIMARY KEY (`row_key`),
    KEY `idx_status` (`status`),
    KEY `idx_branch_id` (`branch_id`),
    KEY `idx_xid` (`xid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = 'Seata 2.6.0 隔离探针内部表';

CREATE TABLE IF NOT EXISTS `distributed_lock`
(
    `lock_key`       CHAR(20) NOT NULL COMMENT '调度锁键',
    `lock_value`     VARCHAR(20) NOT NULL COMMENT '调度锁持有者',
    `expire`         BIGINT COMMENT '锁到期时间',
    primary key (`lock_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = 'Seata 2.6.0 隔离探针内部表';

INSERT INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('AsyncCommitting', ' ', 0);
INSERT INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('RetryCommitting', ' ', 0);
INSERT INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('RetryRollbacking', ' ', 0);
INSERT INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('TxTimeoutCheck', ' ', 0);


CREATE TABLE IF NOT EXISTS `vgroup_table`
(
    `vGroup`    VARCHAR(255) COMMENT '事务组',
    `namespace` VARCHAR(255) COMMENT '命名空间',
    `cluster`   VARCHAR(255) COMMENT '集群',
  UNIQUE KEY `idx_vgroup_namespace_cluster` (`vGroup`,`namespace`,`cluster`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = 'Seata 2.6.0 隔离探针内部表';
