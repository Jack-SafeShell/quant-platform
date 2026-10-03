import unittest
from unittest.mock import patch
import prepare_dataset as module

def candle(at):
    return [at, '100', '110', '90', '105', '1', at + 3599999]

class PublicDatasetTest(unittest.TestCase):
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
