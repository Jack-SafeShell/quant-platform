-- Additive menu migration for the existing ruoyi-vue-pro base schema.
-- Fixed IDs are reserved for this project and guarded by both ID and business key checks.
INSERT INTO system_menu
  (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
   status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 2000001, '量化研究', '', 1, 25, 0, '/quant', 'ep:data-analysis', '', '',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 2000001 OR (deleted = b'0' AND path = '/quant'));

INSERT INTO system_menu
  (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
   status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 2000002, '历史回测', 'quant:backtest:query', 2, 1, 2000001, 'backtest',
       'ep:trend-charts', 'quant/backtest/index', 'QuantBacktest',
       0, b'1', b'0', b'0', '1', NOW(), '1', NOW(), b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 2000002 OR permission = 'quant:backtest:query');

INSERT INTO system_menu
  (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
   status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 2000003, '提交历史回测', 'quant:backtest:create', 3, 1, 2000002, '', '', '', '',
       0, b'1', b'0', b'0', '1', NOW(), '1', NOW(), b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 2000003 OR permission = 'quant:backtest:create');
