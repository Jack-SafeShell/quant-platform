CREATE TABLE IF NOT EXISTS quant_paper_session (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  batch_id VARCHAR(36) NOT NULL,
  strategy_version_id VARCHAR(36) NOT NULL,
  parameter_set_id VARCHAR(36) NOT NULL,
  admission_evidence_hash VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  UNIQUE KEY uk_quant_paper_session_scope (tenant_id, owner_id, batch_id, parameter_set_id),
  KEY idx_quant_paper_session_owner (tenant_id, owner_id, created_at),
  CONSTRAINT fk_quant_paper_session_batch FOREIGN KEY (batch_id) REFERENCES quant_optimization_batch(id)
);

CREATE TABLE IF NOT EXISTS quant_paper_session_review (
  id VARCHAR(36) PRIMARY KEY,
  session_id VARCHAR(36) NOT NULL,
  reviewer_id BIGINT NOT NULL,
  decision VARCHAR(16) NOT NULL,
  comment VARCHAR(500) NOT NULL,
  admission_evidence_hash VARCHAR(64) NOT NULL,
  created_at BIGINT NOT NULL,
  KEY idx_quant_paper_session_review (session_id, created_at),
  CONSTRAINT fk_quant_paper_session_review FOREIGN KEY (session_id) REFERENCES quant_paper_session(id)
);
