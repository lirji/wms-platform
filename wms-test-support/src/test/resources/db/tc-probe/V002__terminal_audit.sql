-- 仅供S0候选方案验证，禁止当作已批准的生产TC迁移。
CREATE TABLE terminal_evidence (
 xid VARCHAR(128) NOT NULL COMMENT 'TC原始全局事务标识',
 application_id VARCHAR(32) COMMENT 'TC记录的发起应用',
 transaction_service_group VARCHAR(32) COMMENT 'TC记录的事务分组',
 terminal_status TINYINT NOT NULL COMMENT '终态码：9提交、11回滚、13超时回滚',
 recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '数据库记录证据时间',
 PRIMARY KEY (xid),
 CONSTRAINT ck_terminal_status CHECK (terminal_status IN (9,11,13))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TC持久化终态审计候选；非业务决定表';

DELIMITER $$
CREATE TRIGGER audit_terminal_update AFTER UPDATE ON global_table FOR EACH ROW
BEGIN
 IF NEW.status IN (9,11,13) THEN
  IF EXISTS (SELECT 1 FROM terminal_evidence WHERE xid=NEW.xid AND terminal_status<>NEW.status) THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='conflicting TC terminal evidence';
  END IF;
  INSERT INTO terminal_evidence (xid,application_id,transaction_service_group,terminal_status)
   VALUES (NEW.xid,NEW.application_id,NEW.transaction_service_group,NEW.status)
   ON DUPLICATE KEY UPDATE xid=NEW.xid;
 END IF;
END$$
CREATE TRIGGER audit_terminal_delete BEFORE DELETE ON global_table FOR EACH ROW
BEGIN
 -- 非终态清理不生成成功证据；缺证据应持续阻断业务放行。
 IF OLD.status IN (9,11,13) THEN
  IF EXISTS (SELECT 1 FROM terminal_evidence WHERE xid=OLD.xid AND terminal_status<>OLD.status) THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='conflicting TC terminal evidence';
  END IF;
  INSERT INTO terminal_evidence (xid,application_id,transaction_service_group,terminal_status)
   VALUES (OLD.xid,OLD.application_id,OLD.transaction_service_group,OLD.status)
   ON DUPLICATE KEY UPDATE xid=OLD.xid;
 END IF;
END$$
DELIMITER ;
