CREATE TABLE IF NOT EXISTS quant_paper_command_preview (
  id VARCHAR(36) PRIMARY KEY,
  execution_id VARCHAR(36) NOT NULL,
  preview_json TEXT NOT NULL,
  preview_hash VARCHAR(64) NOT NULL,
  work_directory VARCHAR(500) NOT NULL,
  created_at BIGINT NOT NULL,
  UNIQUE KEY uk_quant_paper_preview_execution (execution_id),
  CONSTRAINT fk_quant_paper_preview_execution FOREIGN KEY (execution_id) REFERENCES quant_paper_execution(id)
);
