-- Signed cumulative exchange costs, NULL denotes missing historical evidence.
ALTER TABLE quant_live_exchange_order ADD COLUMN fee_amount DECIMAL(28,12) NULL;
ALTER TABLE quant_live_exchange_order ADD COLUMN fee_currency VARCHAR(16) NULL;
ALTER TABLE quant_live_exchange_order ADD COLUMN rebate_amount DECIMAL(28,12) NULL;
ALTER TABLE quant_live_exchange_order ADD COLUMN rebate_currency VARCHAR(16) NULL;
