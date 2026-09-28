ALTER TABLE quant_paper_execution DROP INDEX uk_quant_paper_execution_session;
ALTER TABLE quant_paper_execution ADD KEY idx_quant_paper_execution_session (tenant_id, owner_id, session_id, created_at);
