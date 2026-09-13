-- 仅删除 TESTFL 前缀出库数据。先清作业产生的子表。

DELETE FROM outbound_task
WHERE enterprise_id='ENT-DEMO'
  AND (id LIKE 'TESTFL-%' OR document_id LIKE 'TESTFL-%');

DELETE FROM outbound_line
WHERE enterprise_id='ENT-DEMO'
  AND id LIKE 'TESTFL-%';

DELETE FROM outbound_order
WHERE enterprise_id='ENT-DEMO'
  AND id LIKE 'TESTFL-%';
