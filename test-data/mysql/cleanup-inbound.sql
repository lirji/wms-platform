-- 仅删除 TESTFL 前缀入库数据。先清作业产生的子表。

DELETE FROM quality_inspection
WHERE enterprise_id='ENT-DEMO'
  AND (id LIKE 'TESTFL-%' OR inbound_line_id LIKE 'TESTFL-%');

DELETE FROM inbound_task
WHERE enterprise_id='ENT-DEMO'
  AND (id LIKE 'TESTFL-%' OR document_id LIKE 'TESTFL-%');

DELETE FROM inbound_line
WHERE enterprise_id='ENT-DEMO'
  AND id LIKE 'TESTFL-%';

DELETE FROM inbound_order
WHERE enterprise_id='ENT-DEMO'
  AND id LIKE 'TESTFL-%';
