-- Apply only to the selected quant-platform database. Additive; no DROP or DELETE.
CREATE TABLE IF NOT EXISTS quant_strategy (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS quant_strategy_version (
  id VARCHAR(36) PRIMARY KEY,
  strategy_id VARCHAR(36) NOT NULL,
  source_code TEXT NOT NULL,
  source_hash VARCHAR(64) NOT NULL,
  FOREIGN KEY (strategy_id) REFERENCES quant_strategy(id)
);
CREATE TABLE IF NOT EXISTS quant_parameter_set (
  id VARCHAR(36) PRIMARY KEY,
  parameters_json TEXT NOT NULL,
  parameters_hash VARCHAR(64) NOT NULL
);
CREATE TABLE IF NOT EXISTS quant_backtest_task (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  request_key VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  strategy_version_id VARCHAR(36) NOT NULL,
  parameter_set_id VARCHAR(36) NOT NULL,
  dataset_id VARCHAR(64) NOT NULL,
  dataset_hash VARCHAR(64) NOT NULL,
  dataset_source TEXT NOT NULL,
  exchange_name VARCHAR(16) NOT NULL,
  engine_image VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL,
  error_message VARCHAR(500),
  created_at BIGINT NOT NULL,
  started_at BIGINT,
  finished_at BIGINT,
  UNIQUE (tenant_id, owner_id, request_key),
  FOREIGN KEY (strategy_version_id) REFERENCES quant_strategy_version(id),
  FOREIGN KEY (parameter_set_id) REFERENCES quant_parameter_set(id)
);
CREATE TABLE IF NOT EXISTS quant_backtest_result (
  task_id VARCHAR(36) PRIMARY KEY,
  engine_version VARCHAR(64) NOT NULL,
  artifact_hash VARCHAR(64) NOT NULL,
  result_json LONGTEXT NOT NULL,
  FOREIGN KEY (task_id) REFERENCES quant_backtest_task(id)
);
