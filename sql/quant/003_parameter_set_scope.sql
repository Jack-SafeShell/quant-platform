-- Add ownership to reusable parameter sets. Additive; preserves existing task references.
ALTER TABLE quant_parameter_set ADD COLUMN tenant_id BIGINT NULL AFTER id;
ALTER TABLE quant_parameter_set ADD COLUMN owner_id BIGINT NULL AFTER tenant_id;
ALTER TABLE quant_backtest_task ADD COLUMN request_json TEXT NULL AFTER request_hash;
UPDATE quant_parameter_set p JOIN quant_backtest_task t ON t.parameter_set_id=p.id
SET p.tenant_id=t.tenant_id,p.owner_id=t.owner_id
WHERE p.tenant_id IS NULL OR p.owner_id IS NULL;
UPDATE quant_backtest_task t JOIN quant_parameter_set p ON p.id=t.parameter_set_id
SET t.request_json=p.parameters_json WHERE t.request_json IS NULL;
