"""Opt-in real backtest verification. Credentials stay in child environment, not CLI/logs."""
import os
from pathlib import Path
import subprocess
import tempfile
import yaml

root = Path(__file__).resolve().parents[2]
parts = list(yaml.safe_load_all((root / 'yudao-server/src/main/resources/application-local.yaml').read_text(encoding='utf-8')))
cfg = next(p['spring']['datasource']['dynamic']['datasource']['master'] for p in parts if p and 'datasource' in p.get('spring', {}))
env = os.environ.copy()
env.update(QUANT_SMOKE='true', QUANT_TEST_JDBC_URL=cfg['url'], QUANT_TEST_DB_USER=str(cfg['username']),
           QUANT_TEST_DB_PASSWORD=str(cfg['password']), QUANT_WORKSPACE=str(root / '.runtime/quant'))
env['JAVA_HOME'] = r'C:\Users\Jack\.jdks\corretto-25.0.4'
env['PATH'] = env['JAVA_HOME'] + r'\bin;' + env['PATH']
log = Path(tempfile.gettempdir()) / 'quant-platform-backtest/smoke.log'
log.parent.mkdir(parents=True, exist_ok=True)
with log.open('w', encoding='utf-8') as out:
    completed = subprocess.run(['mvn.cmd', '-B', '-pl', 'yudao-server', '-am', '-Dtest=QuantBacktestTest,FreqtradeEngineTest,QuantSmokeTest,QuantMonolithConfigurationTest',
                                '-Dsurefire.failIfNoSpecifiedTests=false', 'package'], cwd=str(root), env=env, stdout=out, stderr=subprocess.STDOUT)
print('SMOKE_EXIT', completed.returncode, 'LOG', log)
raise SystemExit(completed.returncode)
