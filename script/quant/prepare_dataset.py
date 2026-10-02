"""Download public OKX BTC/USDT 1h candles; never load exchange account credentials.

Host Python stdlib only. Example:
  python script/quant/prepare_dataset.py --id okx-btc-202608 --start 2026-08-01 --end 2026-09-01
Dates are UTC; end is exclusive. Includes 240 warmup candles. Existing IDs are immutable.
"""
import argparse
import datetime as dt
import hashlib
import http.client
import json
import math
from pathlib import Path
import re
import time
import urllib.parse
import urllib.request
import urllib.error


def fetch_json(request, attempts=4, opener=None):
    for attempt in range(attempts):
        try:
            with (opener.open(request, timeout=30) if opener else urllib.request.urlopen(request, timeout=30)) as response:
                return json.load(response)
        except (urllib.error.URLError, http.client.IncompleteRead, TimeoutError, ConnectionError):
            if attempt + 1 == attempts:
                raise
            time.sleep(1.5 * (attempt + 1))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--id', required=True)
    parser.add_argument('--start', required=True)
    parser.add_argument('--end', required=True)
    parser.add_argument('--workspace', default=str(Path(__file__).resolve().parents[2] / '.runtime/quant'))
    parser.add_argument('--proxy', default='', help='Host Python HTTP(S) proxy; no account credentials')
    args = parser.parse_args()
    opener = None
    if args.proxy:
        proxy = urllib.parse.urlparse(args.proxy)
        if proxy.scheme not in ('http', 'https') or not proxy.hostname or proxy.username or proxy.password:
            parser.error('Use an HTTP(S) proxy without embedded credentials')
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({'http': args.proxy, 'https': args.proxy}))
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', args.id):
        parser.error('Invalid dataset ID')
    start = dt.datetime.strptime(args.start, '%Y-%m-%d').replace(tzinfo=dt.timezone.utc)
    end = dt.datetime.strptime(args.end, '%Y-%m-%d').replace(tzinfo=dt.timezone.utc)
    if not 0 < (end - start).days <= 366 or end > dt.datetime.now(dt.timezone.utc):
        parser.error('Use 1..366 complete historical UTC days')
    directory = Path(args.workspace).resolve() / 'datasets' / args.id
    if directory.exists():
        parser.error('Dataset ID exists; choose a new ID (no overwrite)')
    first = int(start.timestamp() * 1000) - 240 * 3600000
    stop = int(end.timestamp() * 1000)
    cursor = stop
    candles = {}
    endpoint = 'https://www.okx.com/api/v5/market/history-candles'
    while cursor > first:
        query = urllib.parse.urlencode({'instId': 'BTC-USDT', 'bar': '1H', 'limit': '100', 'after': cursor})
        request = urllib.request.Request(endpoint + '?' + query, headers={'User-Agent': 'Mozilla/5.0'})
        payload = fetch_json(request, opener=opener)
        if payload.get('code') != '0' or not payload.get('data'):
            raise RuntimeError('Public candle API did not return data')
        oldest = min(int(row[0]) for row in payload['data'])
        if oldest >= cursor:
            raise RuntimeError('Pagination made no progress')
        for row in payload['data']:
            timestamp = int(row[0])
            if first <= timestamp < stop:
                if row[8] != '1':
                    raise RuntimeError('Unconfirmed candle')
                values = [float(value) for value in row[1:6]]
                if any(not math.isfinite(value) or value < 0 for value in values):
                    raise RuntimeError('Invalid OHLCV value')
                o, h, low, c, _ = values
                if min(o, h, low, c) <= 0 or h < max(o, c, low) or low > min(o, c, h):
                    raise RuntimeError('Invalid OHLC range')
                candle = [timestamp] + values
                if timestamp in candles and candles[timestamp] != candle:
                    raise RuntimeError('Conflicting candle')
                candles[timestamp] = candle
        cursor = oldest
        time.sleep(0.15)
    expected = list(range(first, stop, 3600000))
    if sorted(candles) != expected:
        raise RuntimeError('Candle gaps or duplicates; dataset not published')
    content = json.dumps([candles[ts] for ts in expected], separators=(',', ':')).encode('utf-8')
    manifest = {'exchange': 'okx', 'pair': 'BTC/USDT', 'timeframe': '1h', 'tradingMode': 'spot',
                'sha256': hashlib.sha256(content).hexdigest(), 'source': endpoint,
                'fetchedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'candles': len(expected),
                'startDate': args.start, 'endDate': args.end, 'warmupCandles': 240}
    directory.mkdir(parents=True, exist_ok=False)
    (directory / 'BTC_USDT-1h.json').write_bytes(content)
    (directory / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print('DATASET_OK', args.id, len(expected), manifest['sha256'])


if __name__ == '__main__':
    main()
