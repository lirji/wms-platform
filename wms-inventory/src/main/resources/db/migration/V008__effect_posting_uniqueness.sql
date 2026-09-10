-- S2-07：过账按效果唯一。命令唯一键保持 effect+action+attempt_no，禁止回到 effect+action 单命令。

ALTER TABLE stock_posting DROP INDEX uk_stock_posting_effect;
ALTER TABLE stock_posting ADD UNIQUE KEY uk_stock_posting_effect (
  enterprise_id, warehouse_id, source_service, business_effect_key);
