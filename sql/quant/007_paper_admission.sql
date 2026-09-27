CREATE TABLE IF NOT EXISTS quant_paper_admission_review (
  id VARCHAR(36) PRIMARY KEY,
  batch_id VARCHAR(36) NOT NULL,
  reviewer_id BIGINT NOT NULL,
  decision VARCHAR(16) NOT NULL,
  comment VARCHAR(500) NOT NULL,
  evidence_hash VARCHAR(64) NOT NULL,
  created_at BIGINT NOT NULL,
  KEY idx_quant_paper_admission_batch (batch_id, created_at),
  CONSTRAINT fk_quant_paper_admission_batch FOREIGN KEY (batch_id) REFERENCES quant_optimization_batch(id)
);
