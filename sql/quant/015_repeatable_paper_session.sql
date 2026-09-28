ALTER TABLE quant_paper_session DROP INDEX uk_quant_paper_session_scope;
ALTER TABLE quant_paper_session ADD KEY idx_quant_paper_session_scope (tenant_id, owner_id, batch_id, parameter_set_id, created_at);
