-- Append-only evidence and dispositions; no session restart or trading permission changes.
CREATE TABLE IF NOT EXISTS quant_live_automation_alert_action (
  id VARCHAR(36) PRIMARY KEY,
  alert_id VARCHAR(36) NOT NULL,
  session_id VARCHAR(36) NOT NULL,
  policy_id VARCHAR(36) NOT NULL,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  action_type VARCHAR(16) NOT NULL,
  from_status VARCHAR(16) NOT NULL,
  to_status VARCHAR(16) NOT NULL,
  comment VARCHAR(500),
  evidence_hash VARCHAR(64) NOT NULL,
  evidence_json MEDIUMTEXT NOT NULL,
  created_at BIGINT NOT NULL,
  expires_at BIGINT,
  KEY idx_quant_live_alert_action (tenant_id, owner_id, session_id, created_at),
  CONSTRAINT fk_quant_live_alert_action_alert FOREIGN KEY (alert_id) REFERENCES quant_live_automation_alert(id),
  CONSTRAINT fk_quant_live_alert_action_session FOREIGN KEY (session_id) REFERENCES quant_live_automation_session(id)
);
