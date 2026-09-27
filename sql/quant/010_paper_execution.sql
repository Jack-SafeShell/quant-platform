CREATE TABLE IF NOT EXISTS quant_paper_execution (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  session_id VARCHAR(36) NOT NULL,
  readiness_snapshot_id VARCHAR(36) NOT NULL,
  readiness_hash VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  container_name VARCHAR(80),
  error_message VARCHAR(500),
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  UNIQUE KEY uk_quant_paper_execution_session (tenant_id, owner_id, session_id),
  KEY idx_quant_paper_execution_owner (tenant_id, owner_id, created_at),
  CONSTRAINT fk_quant_paper_execution_session FOREIGN KEY (session_id) REFERENCES quant_paper_session(id),
  CONSTRAINT fk_quant_paper_execution_readiness FOREIGN KEY (readiness_snapshot_id) REFERENCES quant_paper_readiness_snapshot(id)
);

CREATE TABLE IF NOT EXISTS quant_paper_execution_audit (
  id VARCHAR(36) PRIMARY KEY,
  execution_id VARCHAR(36) NOT NULL,
  actor_id BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  from_status VARCHAR(24),
  to_status VARCHAR(24) NOT NULL,
  message VARCHAR(500) NOT NULL,
  created_at BIGINT NOT NULL,
  KEY idx_quant_paper_execution_audit (execution_id, created_at),
  CONSTRAINT fk_quant_paper_execution_audit FOREIGN KEY (execution_id) REFERENCES quant_paper_execution(id)
);
