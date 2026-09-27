CREATE TABLE IF NOT EXISTS quant_paper_start_token (
  id VARCHAR(36) PRIMARY KEY,
  execution_id VARCHAR(36) NOT NULL,
  preview_hash VARCHAR(64) NOT NULL,
  token_hash VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  confirmation_comment VARCHAR(500) NOT NULL,
  issued_by BIGINT NOT NULL,
  issued_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  consumed_at BIGINT,
  UNIQUE KEY uk_quant_paper_start_token_hash (token_hash),
  KEY idx_quant_paper_start_token_execution (execution_id, issued_at),
  CONSTRAINT fk_quant_paper_start_token_execution FOREIGN KEY (execution_id) REFERENCES quant_paper_execution(id)
);
