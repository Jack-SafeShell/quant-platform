-- Independent, frozen historical parameter neighbourhoods; no execution state.
CREATE TABLE IF NOT EXISTS quant_parameter_stability (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  request_key VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  plan_json LONGTEXT NOT NULL,
  task_ids_json TEXT NOT NULL,
  created_at BIGINT NOT NULL,
  UNIQUE (tenant_id, owner_id, request_key)
);
