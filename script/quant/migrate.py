"""Apply this project's additive quant schema and admin menu, using local master credentials.

Requires existing PyYAML and PyMySQL. Does not import upstream demo data or modify other schemas.
"""
from pathlib import Path
from urllib.parse import urlparse
import pymysql
import yaml

root = Path(__file__).resolve().parents[2]
parts = list(yaml.safe_load_all((root / 'yudao-server/src/main/resources/application-local.yaml').read_text(encoding='utf-8')))
cfg = next(p['spring']['datasource']['dynamic']['datasource']['master'] for p in parts if p and 'datasource' in p.get('spring', {}))
url = urlparse(cfg['url'][5:])
if url.path != '/quant-platform':
    raise SystemExit('Refusing non-project database')
conn = pymysql.connect(host=url.hostname, port=url.port or 3306, user=cfg['username'], password=str(cfg['password']),
                       database='quant-platform', charset='utf8mb4', connect_timeout=10)
try:
    cursor = conn.cursor()
    for migration in ('001_backtest.sql', '002_backtest_menu.sql', '003_parameter_set_scope.sql', '004_dataset_download.sql', '005_optimization_batch.sql', '006_research_review.sql', '007_paper_admission.sql', '008_paper_session.sql', '009_paper_readiness.sql', '010_paper_execution.sql', '011_paper_command_preview.sql', '012_paper_start_token.sql', '013_paper_observation_alert.sql', '014_repeatable_paper_execution.sql', '015_repeatable_paper_session.sql', '016_paper_alert_workflow.sql', '017_paper_order_ledger.sql', '018_live_admission.sql', '019_live_control.sql', '020_live_order_execution.sql', '021_live_automation.sql', '022_operations_dashboard_menu.sql', '023_strategy_experiment.sql', '024_strategy_workbench_menu.sql', '025_live_order_costs.sql'):
        sql = (root / 'sql/quant' / migration).read_text(encoding='utf-8')
        sql = '\n'.join(line for line in sql.splitlines() if not line.lstrip().startswith('--'))
        for statement in sql.split(';'):
            statement = statement.strip()
            if not statement:
                continue
            if not (statement.startswith('CREATE TABLE IF NOT EXISTS quant_')
                    or statement.startswith('INSERT INTO system_menu')
                    or statement.startswith('ALTER TABLE quant_live_exchange_order')
                    or statement.startswith('ALTER TABLE quant_parameter_set')
                    or statement.startswith('ALTER TABLE quant_backtest_task')
                    or statement.startswith('ALTER TABLE quant_paper_execution')
                    or statement.startswith('ALTER TABLE quant_paper_session')
                    or statement.startswith('ALTER TABLE quant_paper_alert')
                    or statement.startswith('UPDATE quant_parameter_set')
                    or statement.startswith('UPDATE quant_backtest_task')):
                raise RuntimeError(f'Unexpected migration statement in {migration}')
            try:
                cursor.execute(statement)
            except pymysql.MySQLError as error:
                repeatable_index = migration in ('014_repeatable_paper_execution.sql', '015_repeatable_paper_session.sql') and error.args[0] in (1061, 1091)
                if not ((statement.startswith('ALTER TABLE quant_') and ' ADD COLUMN ' in statement and error.args[0] == 1060)
                        or repeatable_index):
                    raise
    conn.commit()
    print('QUANT_MIGRATIONS_OK (37 tables, scoped parameter sets, 5 menu records)')
finally:
    conn.close()
