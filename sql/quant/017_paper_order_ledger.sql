CREATE TABLE IF NOT EXISTS quant_paper_order (
  id VARCHAR(36) PRIMARY KEY,
  execution_id VARCHAR(36) NOT NULL,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  idempotency_key VARCHAR(64) NOT NULL,
  source_order_id VARCHAR(128) NOT NULL,
  trade_id VARCHAR(128) NULL,
  pair_symbol VARCHAR(32) NOT NULL,
  order_side VARCHAR(16) NOT NULL,
  order_type VARCHAR(24) NOT NULL,
  order_status VARCHAR(24) NOT NULL,
  price DECIMAL(24,8) NOT NULL DEFAULT 0,
  amount DECIMAL(24,8) NOT NULL DEFAULT 0,
  filled DECIMAL(24,8) NOT NULL DEFAULT 0,
  cost DECIMAL(24,8) NOT NULL DEFAULT 0,
  source_created_at VARCHAR(64) NULL,
  source_updated_at VARCHAR(64) NULL,
  reconciliation_status VARCHAR(16) NOT NULL,
  first_seen_at BIGINT NOT NULL,
  last_seen_at BIGINT NOT NULL,
  UNIQUE KEY uk_quant_paper_order_source (execution_id, source_order_id),
  UNIQUE KEY uk_quant_paper_order_idempotency (execution_id, idempotency_key),
  KEY idx_quant_paper_order_owner (tenant_id, owner_id, execution_id, last_seen_at),
  CONSTRAINT fk_quant_paper_order_execution FOREIGN KEY (execution_id) REFERENCES quant_paper_execution(id)
);

CREATE TABLE IF NOT EXISTS quant_paper_order_audit (
  id VARCHAR(36) PRIMARY KEY,
  order_id VARCHAR(36) NOT NULL,
  execution_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(24) NOT NULL,
  from_status VARCHAR(24) NULL,
  to_status VARCHAR(24) NOT NULL,
  evidence VARCHAR(500) NOT NULL,
  created_at BIGINT NOT NULL,
  KEY idx_quant_paper_order_audit_order (order_id, created_at),
  CONSTRAINT fk_quant_paper_order_audit_order FOREIGN KEY (order_id) REFERENCES quant_paper_order(id),
  CONSTRAINT fk_quant_paper_order_audit_execution FOREIGN KEY (execution_id) REFERENCES quant_paper_execution(id)
);

CREATE TABLE IF NOT EXISTS quant_paper_order_reconciliation (
  id VARCHAR(36) PRIMARY KEY,
  execution_id VARCHAR(36) NOT NULL,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  source_order_count INT NOT NULL,
  ledger_order_count INT NOT NULL,
  unknown_order_count INT NOT NULL,
  reconciliation_status VARCHAR(16) NOT NULL,
  evidence_hash VARCHAR(64) NOT NULL,
  error_message VARCHAR(500) NULL,
  reconciled_at BIGINT NOT NULL,
  KEY idx_quant_paper_reconciliation_owner (tenant_id, owner_id, execution_id, reconciled_at),
  CONSTRAINT fk_quant_paper_reconciliation_execution FOREIGN KEY (execution_id) REFERENCES quant_paper_execution(id)
);
