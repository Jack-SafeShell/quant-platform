-- Persistent account mutex and immutable reservation time; existing ownership remains in signals.
CREATE TABLE IF NOT EXISTS quant_live_account_guard (id INT PRIMARY KEY);
INSERT IGNORE INTO quant_live_account_guard(id) VALUES(1);
ALTER TABLE quant_live_exchange_order ADD COLUMN reserved_at BIGINT;
ALTER TABLE quant_live_exchange_order ADD UNIQUE KEY uk_quant_live_account_client (client_order_id);
