import unittest
from unittest.mock import patch
import prepare_dataset as module

def candle(at):
    return [at, '100', '110', '90', '105', '1', at + 3599999]

class PublicDatasetTest(unittest.TestCase):
    @patch.object(module.time, 'sleep')
    def test_market_and_four_hour_pagination_are_bound_without_credentials(self, _):
        for exchange in ('okx', 'binance'):
            for pair in ('BTC/USDT', 'ETH/USDT'):
                for timeframe, interval in (('1h', 3600000), ('4h', 14400000)):
                    requests = []
                    def fetch(request, **kwargs):
                        requests.append(request)
                        q = module.urllib.parse.parse_qs(module.urllib.parse.urlparse(request.full_url).query)
                        self.assertEqual(pair.replace('/', '' if exchange == 'binance' else '-'), q['symbol' if exchange == 'binance' else 'instId'][0])
                        self.assertEqual(timeframe if exchange == 'binance' else timeframe.upper(), q['interval' if exchange == 'binance' else 'bar'][0])
                        at = int(q['startTime'][0]) if exchange == 'binance' else int(q['after'][0]) - interval
                        row = candle(at); row[6] = at + interval - 1
                        return [row] if exchange == 'binance' else {'code':'0','data':[row[:6]+['0','0','1']]}
                    with self.subTest(exchange=exchange,pair=pair,timeframe=timeframe), patch.object(module, 'fetch_json', side_effect=fetch):
                        rows, _ = module.download_candles(exchange, 0, 2*interval, pair=pair, timeframe=timeframe)
                    self.assertEqual([0, interval], sorted(rows))
                    self.assertEqual(2, len(requests))
                    self.assertTrue(all('X-mbx-apikey' not in r.headers for r in requests))

    def test_rejects_unsupported_or_unaligned_market_before_network(self):
        with patch.object(module, 'fetch_json') as fetch:
            for pair, timeframe, first in [('ETH/USDT','5m',0),('ETH/USDT','4h',3600000),('../ETH','1h',0)]:
                with self.assertRaises(ValueError): module.download_candles('binance',first,28800000,pair=pair,timeframe=timeframe)
            fetch.assert_not_called()

    @patch.object(module.time, 'sleep')
    def test_four_hour_rejects_hourly_close_and_unconfirmed_okx(self, _):
        for exchange, payload in [('binance',[candle(0)]),('okx',{'code':'0','data':[candle(0)[:6]+['0','0','0']]})]:
            with patch.object(module, 'fetch_json', return_value=payload), self.assertRaises(RuntimeError):
                module.download_candles(exchange,0,14400000,pair='ETH/USDT',timeframe='4h')

    @patch.object(module.time, 'sleep')
    def test_binance_paginates_forward_without_keys(self, _):
        requests = []
        def fetch(request, **kwargs):
            requests.append(request)
            start = int(module.urllib.parse.parse_qs(module.urllib.parse.urlparse(request.full_url).query)['startTime'][0])
            return [candle(start)]
        with patch.object(module, 'fetch_json', side_effect=fetch):
            rows, endpoint = module.download_candles('binance', 0, 7200000)
        self.assertEqual([0, 3600000], sorted(rows))
        self.assertIn('data-api.binance.vision', endpoint)
        self.assertNotIn('X-mbx-apikey', requests[0].headers)
        self.assertIn('endTime=7199999', requests[0].full_url)

    @patch.object(module.time, 'sleep')
    def test_okx_backward_pagination_remains_supported(self, _):
        rows = [candle(0)[:6] + ['0', '0', '1'], candle(3600000)[:6] + ['0', '0', '1']]
        with patch.object(module, 'fetch_json', return_value={'code': '0', 'data': rows}):
            candles, _ = module.download_candles('okx', 0, 7200000)
        self.assertEqual([0, 3600000], sorted(candles))

    @patch.object(module.time, 'sleep')
    def test_rejects_gaps_duplicates_and_nonfinite_values(self, _):
        invalid = [[candle(3600000)], [candle(0), candle(0)], [candle(0)[:1] + ['NaN'] + candle(0)[2:]]]
        for rows in invalid:
            with self.subTest(rows=rows), patch.object(module, 'fetch_json', return_value=rows):
                with self.assertRaises(RuntimeError):
                    module.download_candles('binance', 0, 7200000)

    @patch.object(module.time, 'sleep')
    def test_rejects_wrong_close_time_and_error_payload(self, _):
        row = candle(0);row[6] = 1
        for payload in [[row], {'code': -1, 'msg': 'bad'}]:
            with patch.object(module, 'fetch_json', return_value=payload):
                with self.assertRaises(RuntimeError):module.download_candles('binance', 0, 3600000)

if __name__ == '__main__':
    unittest.main()
