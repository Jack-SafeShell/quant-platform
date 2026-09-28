ALTER TABLE quant_paper_alert ADD COLUMN acknowledged_at BIGINT NULL;
ALTER TABLE quant_paper_alert ADD COLUMN acknowledged_by BIGINT NULL;
ALTER TABLE quant_paper_alert ADD COLUMN resolution_comment VARCHAR(500) NULL;

CREATE TABLE IF NOT EXISTS quant_paper_alert_action (
  id VARCHAR(36) PRIMARY KEY,
  alert_id VARCHAR(36) NOT NULL,
  execution_id VARCHAR(36) NOT NULL,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  action_type VARCHAR(16) NOT NULL,
  from_status VARCHAR(16) NOT NULL,
  to_status VARCHAR(16) NOT NULL,
  comment VARCHAR(500) NOT NULL,
  created_at BIGINT NOT NULL,
  KEY idx_quant_paper_alert_action_alert (alert_id, created_at),
  KEY idx_quant_paper_alert_action_owner (tenant_id, owner_id, created_at),
  CONSTRAINT fk_quant_paper_alert_action_alert FOREIGN KEY (alert_id) REFERENCES quant_paper_alert(id),
  CONSTRAINT fk_quant_paper_alert_action_execution FOREIGN KEY (execution_id) REFERENCES quant_paper_execution(id)
);
