-- Additive menu entry for the unified quant operations dashboard.
INSERT INTO system_menu
  (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
   status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 2000004, '运行面板', 'quant:backtest:query', 2, 0, 2000001, 'operations',
       'ep:monitor', 'quant/operations/index', 'QuantOperations',
       0, b'1', b'0', b'0', '1', NOW(), '1', NOW(), b'0'
WHERE NOT EXISTS (
  SELECT 1 FROM system_menu
  WHERE id = 2000004 OR (deleted = b'0' AND parent_id = 2000001 AND path = 'operations')
);
