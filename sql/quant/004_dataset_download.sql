CREATE TABLE IF NOT EXISTS quant_dataset_download_task (
  id VARCHAR(36) PRIMARY KEY, tenant_id BIGINT NOT NULL, owner_id BIGINT NOT NULL,
  request_key VARCHAR(64) NOT NULL, request_hash VARCHAR(64) NOT NULL,
  dataset_id VARCHAR(64) NOT NULL, start_date VARCHAR(10) NOT NULL, end_date VARCHAR(10) NOT NULL,
  exchange_name VARCHAR(16) NOT NULL, pair_name VARCHAR(32) NOT NULL, timeframe VARCHAR(8) NOT NULL,
  status VARCHAR(16) NOT NULL, error_message VARCHAR(500), dataset_hash VARCHAR(64), candles INT,
  created_at BIGINT NOT NULL, started_at BIGINT, finished_at BIGINT,
  UNIQUE KEY uk_quant_download_request (tenant_id, owner_id, request_key),
  UNIQUE KEY uk_quant_download_dataset (dataset_id), KEY idx_quant_download_queue (status, created_at)
);
CREATE TABLE IF NOT EXISTS quant_dataset_download_audit (
  id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL, event_type VARCHAR(16) NOT NULL,
  detail VARCHAR(255) NOT NULL, created_at BIGINT NOT NULL,
  KEY idx_quant_download_audit (task_id, created_at),
  CONSTRAINT fk_quant_download_audit_task FOREIGN KEY (task_id) REFERENCES quant_dataset_download_task(id)
);
