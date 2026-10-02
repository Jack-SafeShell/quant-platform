-- Shared conditions across several immutable strategy versions. Additive only.
CREATE TABLE IF NOT EXISTS quant_strategy_experiment (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  request_key VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  dataset_id VARCHAR(64) NOT NULL,
  parameter_set_id VARCHAR(36) NOT NULL,
  train_start VARCHAR(10) NOT NULL,
  split_date VARCHAR(10) NOT NULL,
  validation_end VARCHAR(10) NOT NULL,
  created_at BIGINT NOT NULL,
  UNIQUE (tenant_id, owner_id, request_key),
  FOREIGN KEY (parameter_set_id) REFERENCES quant_parameter_set(id)
);
CREATE TABLE IF NOT EXISTS quant_strategy_experiment_member (
  experiment_id VARCHAR(36) NOT NULL,
  strategy_version_id VARCHAR(36) NOT NULL,
  optimization_batch_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (experiment_id, strategy_version_id),
  UNIQUE (optimization_batch_id),
  FOREIGN KEY (experiment_id) REFERENCES quant_strategy_experiment(id),
  FOREIGN KEY (strategy_version_id) REFERENCES quant_strategy_version(id),
  FOREIGN KEY (optimization_batch_id) REFERENCES quant_optimization_batch(id)
);
