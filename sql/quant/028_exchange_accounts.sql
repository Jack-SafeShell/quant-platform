-- Existing live history belongs permanently to the original OKX account.
CREATE TABLE IF NOT EXISTS quant_exchange_account (
  id VARCHAR(64) PRIMARY KEY, exchange_name VARCHAR(16) NOT NULL,
  credential_file_name VARCHAR(80) NOT NULL, identity_hash VARCHAR(64),
  created_at BIGINT NOT NULL,
  UNIQUE KEY uk_quant_account_identity (exchange_name, identity_hash)
);
INSERT IGNORE INTO quant_exchange_account(id,exchange_name,credential_file_name,created_at)
VALUES('okx-primary','okx','okx-live.dpapi',0);
ALTER TABLE quant_live_control_policy ADD COLUMN account_id VARCHAR(64) NOT NULL DEFAULT 'okx-primary';
ALTER TABLE quant_live_exchange_order ADD COLUMN account_id VARCHAR(64) NOT NULL DEFAULT 'okx-primary';
ALTER TABLE quant_live_portfolio ADD COLUMN account_id VARCHAR(64) NOT NULL DEFAULT 'okx-primary';
CREATE INDEX idx_quant_order_account ON quant_live_exchange_order(account_id,status,reserved_at);
CREATE INDEX idx_quant_policy_account ON quant_live_control_policy(account_id);
