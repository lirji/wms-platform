ALTER TABLE reconciliation_cutoff
 ADD COLUMN evidence_version INT NOT NULL DEFAULT 0 COMMENT '0为历史或调用方声明；1仅由三方核验成功路径设置' AFTER watermarks_complete,
 ADD CONSTRAINT ck_reconciliation_evidence_version CHECK(evidence_version IN (0,1));
