"""Download public OKX/Binance BTC/USDT 1h candles; never load exchange account credentials.

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


def download_candles(exchange, first, stop, opener=None):
    if exchange not in ('okx', 'binance') or first % 3600000 or stop % 3600000 or first >= stop:
        raise ValueError('Invalid exchange or candle range')
    binance = exchange == 'binance'
    endpoint = ('https://data-api.binance.vision/api/v3/klines' if binance
                else 'https://www.okx.com/api/v5/market/history-candles')
    cursor = first if binance else stop
    candles = {}
    while cursor < stop if binance else cursor > first:
        params = ({'symbol': 'BTCUSDT', 'interval': '1h', 'limit': 1000,
                   'startTime': cursor, 'endTime': stop - 1} if binance else
                  {'instId': 'BTC-USDT', 'bar': '1H', 'limit': '100', 'after': cursor})
        request = urllib.request.Request(endpoint + '?' + urllib.parse.urlencode(params),
                                         headers={'User-Agent': 'quant-platform/1.0'})
        payload = fetch_json(request, opener=opener)
        rows = payload if binance else payload.get('data', [])
        if not isinstance(rows, list) or not rows or (not binance and payload.get('code') != '0'):
            raise RuntimeError('Public candle API did not return data')
        times = []
        for row in rows:
            if not isinstance(row, list) or len(row) < (7 if binance else 9):
                raise RuntimeError('Incomplete candle')
            timestamp = int(row[0])
            times.append(timestamp)
            if timestamp % 3600000:
                raise RuntimeError('Unaligned candle')
            if first <= timestamp < stop:
                if (binance and int(row[6]) != timestamp + 3600000 - 1) or (not binance and row[8] != '1'):
                    raise RuntimeError('Unconfirmed candle')
                values = [float(value) for value in row[1:6]]
                if any(not math.isfinite(value) or value < 0 for value in values):
                    raise RuntimeError('Invalid OHLCV value')
                o, h, low, c, _ = values
                if min(o, h, low, c) <= 0 or h < max(o, c, low) or low > min(o, c, h):
                    raise RuntimeError('Invalid OHLC range')
                candle = [timestamp] + values
                if timestamp in candles:
                    raise RuntimeError('Duplicate candle')
                candles[timestamp] = candle
        next_cursor = max(times) + 3600000 if binance else min(times)
        if (binance and next_cursor <= cursor) or (not binance and next_cursor >= cursor):
            raise RuntimeError('Pagination made no progress')
        cursor = next_cursor
        time.sleep(0.15)
    if sorted(candles) != list(range(first, stop, 3600000)):
        raise RuntimeError('Candle gaps; dataset not published')
    return candles, endpoint


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--exchange', choices=('okx', 'binance'), default='okx')
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
    candles, endpoint = download_candles(args.exchange, first, stop, opener)
    expected = list(range(first, stop, 3600000))
    content = json.dumps([candles[ts] for ts in expected], separators=(',', ':')).encode('utf-8')
    manifest = {'exchange': args.exchange, 'pair': 'BTC/USDT', 'timeframe': '1h', 'tradingMode': 'spot',
                'sha256': hashlib.sha256(content).hexdigest(), 'source': endpoint,
                'fetchedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'candles': len(expected),
                'startDate': args.start, 'endDate': args.end, 'warmupCandles': 240}
    directory.mkdir(parents=True, exist_ok=False)
    (directory / 'BTC_USDT-1h.json').write_bytes(content)
    (directory / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print('DATASET_OK', args.id, len(expected), manifest['sha256'])


if __name__ == '__main__':
    main()
