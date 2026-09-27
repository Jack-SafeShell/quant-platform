CREATE TABLE IF NOT EXISTS quant_optimization_batch (
 id VARCHAR(36) PRIMARY KEY, tenant_id BIGINT NOT NULL, owner_id BIGINT NOT NULL, dataset_id VARCHAR(64) NOT NULL,
 strategy_version_id VARCHAR(36) NOT NULL, train_start VARCHAR(10) NOT NULL, split_date VARCHAR(10) NOT NULL,
 validation_end VARCHAR(10) NOT NULL, created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS quant_optimization_member (
 batch_id VARCHAR(36) NOT NULL, parameter_set_id VARCHAR(36) NOT NULL, phase VARCHAR(16) NOT NULL, task_id VARCHAR(36) NOT NULL,
 PRIMARY KEY(batch_id,parameter_set_id,phase), UNIQUE KEY uk_quant_opt_task(task_id),
 FOREIGN KEY(batch_id) REFERENCES quant_optimization_batch(id), FOREIGN KEY(task_id) REFERENCES quant_backtest_task(id)
);
