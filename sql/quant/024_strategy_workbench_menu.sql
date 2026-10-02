-- Strategy research and paper preparation entry.
INSERT INTO system_menu
  (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
   status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 2000005, '策略实验工作台', 'quant:backtest:query', 2, 1, 2000001, 'experiments',
       'ep:data-analysis', 'quant/experiments/index', 'QuantExperiments',
       0, b'1', b'0', b'0', '1', NOW(), '1', NOW(), b'0'
WHERE NOT EXISTS (
  SELECT 1 FROM system_menu
  WHERE id = 2000005 OR (deleted = b'0' AND parent_id = 2000001 AND path = 'experiments')
);
