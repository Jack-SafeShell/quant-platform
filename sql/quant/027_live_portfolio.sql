CREATE TABLE IF NOT EXISTS quant_live_portfolio (
 id VARCHAR(36) PRIMARY KEY, tenant_id BIGINT NOT NULL, owner_id BIGINT NOT NULL,
 configuration_json LONGTEXT NOT NULL, evidence_hash VARCHAR(64) NOT NULL,
 total_capital DECIMAL(20,8) NOT NULL, loss_budget DECIMAL(20,8) NOT NULL,
 status VARCHAR(24) NOT NULL, stop_reason VARCHAR(500), created_at BIGINT NOT NULL, started_at BIGINT, stopped_at BIGINT,
 KEY idx_quant_portfolio_owner (tenant_id,owner_id,created_at)
);
CREATE TABLE IF NOT EXISTS quant_live_portfolio_member (
 portfolio_id VARCHAR(36) NOT NULL, report_id VARCHAR(36) NOT NULL, policy_id VARCHAR(36), session_id VARCHAR(36),
 report_hash VARCHAR(64) NOT NULL, capital DECIMAL(20,8) NOT NULL, daily_notional DECIMAL(20,8) NOT NULL,
 PRIMARY KEY (portfolio_id,report_id), UNIQUE KEY uk_quant_portfolio_session(session_id),
 CONSTRAINT fk_quant_portfolio_member FOREIGN KEY (portfolio_id) REFERENCES quant_live_portfolio(id)
);
CREATE TABLE IF NOT EXISTS quant_live_session_exit (
 client_order_id VARCHAR(64) PRIMARY KEY, tenant_id BIGINT NOT NULL, owner_id BIGINT NOT NULL,
 policy_id VARCHAR(36) NOT NULL, session_id VARCHAR(36) NOT NULL, request_id VARCHAR(64) NOT NULL,
 price DECIMAL(20,8) NOT NULL, amount DECIMAL(20,8) NOT NULL, created_at BIGINT NOT NULL,
 UNIQUE KEY uk_quant_exit_request (tenant_id,owner_id,session_id,request_id),
 CONSTRAINT fk_quant_exit_session FOREIGN KEY (session_id) REFERENCES quant_live_automation_session(id)
);
