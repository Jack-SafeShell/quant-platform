CREATE TABLE IF NOT EXISTS quant_paper_readiness_snapshot (
  id VARCHAR(36) PRIMARY KEY,
  session_id VARCHAR(36) NOT NULL,
  manifest_json TEXT NOT NULL,
  manifest_hash VARCHAR(64) NOT NULL,
  ready BOOLEAN NOT NULL,
  created_at BIGINT NOT NULL,
  KEY idx_quant_paper_readiness_session (session_id, created_at),
  CONSTRAINT fk_quant_paper_readiness_session FOREIGN KEY (session_id) REFERENCES quant_paper_session(id)
);
