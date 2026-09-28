CREATE TABLE IF NOT EXISTS quant_live_admission_report (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  report_json LONGTEXT NOT NULL,
  report_hash VARCHAR(64) NOT NULL,
  created_at BIGINT NOT NULL,
  UNIQUE KEY uk_quant_live_admission_hash (tenant_id, owner_id, report_hash),
  KEY idx_quant_live_admission_owner (tenant_id, owner_id, created_at)
);

CREATE TABLE IF NOT EXISTS quant_live_admission_confirmation (
  id VARCHAR(36) PRIMARY KEY,
  report_id VARCHAR(36) NOT NULL,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  confirmation_type VARCHAR(32) NOT NULL,
  confirmation_phrase VARCHAR(64) NOT NULL,
  comment VARCHAR(500) NOT NULL,
  report_hash VARCHAR(64) NOT NULL,
  created_at BIGINT NOT NULL,
  UNIQUE KEY uk_quant_live_confirmation_type (report_id, confirmation_type),
  KEY idx_quant_live_confirmation_owner (tenant_id, owner_id, created_at),
  CONSTRAINT fk_quant_live_confirmation_report FOREIGN KEY (report_id) REFERENCES quant_live_admission_report(id)
);
