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
    for migration in ('001_backtest.sql', '002_backtest_menu.sql', '003_parameter_set_scope.sql', '004_dataset_download.sql', '005_optimization_batch.sql', '006_research_review.sql', '007_paper_admission.sql'):
        sql = (root / 'sql/quant' / migration).read_text(encoding='utf-8')
        sql = '\n'.join(line for line in sql.splitlines() if not line.lstrip().startswith('--'))
        for statement in sql.split(';'):
            statement = statement.strip()
            if not statement:
                continue
            if not (statement.startswith('CREATE TABLE IF NOT EXISTS quant_')
                    or statement.startswith('INSERT INTO system_menu')
                    or statement.startswith('ALTER TABLE quant_parameter_set')
                    or statement.startswith('ALTER TABLE quant_backtest_task')
                    or statement.startswith('UPDATE quant_parameter_set')
                    or statement.startswith('UPDATE quant_backtest_task')):
                raise RuntimeError(f'Unexpected migration statement in {migration}')
            try:
                cursor.execute(statement)
            except pymysql.MySQLError as error:
                if not (statement.startswith('ALTER TABLE quant_') and ' ADD COLUMN ' in statement and error.args[0] == 1060):
                    raise
    conn.commit()
    print('QUANT_MIGRATIONS_OK (11 tables, scoped parameter sets, 3 menu records)')
finally:
    conn.close()
