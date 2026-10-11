"""Run in the pinned Freqtrade image, without network or credentials."""
import unittest
from pathlib import Path
import numpy as np
import pandas as pd
import talib


def strategy():
    source = Path('/workspace/yudao-module-quant/yudao-module-quant-server/src/main/resources/quant/QuantEmaAdx.py').read_text()
    for key, value in {'FAST': '20', 'SLOW': '60', 'ADX_PERIOD': '14', 'ADX_THRESHOLD': '25', 'ROI': '0.06', 'STOP': '0.03'}.items():
        source = source.replace('__' + key + '__', value)
    scope = {'__name__': 'ema_adx_test'}
    exec(compile(source, 'ema_adx_strategy', 'exec'), scope)
    return scope['QuantEmaBaseline']({})


class EmaAdxBehaviorTest(unittest.TestCase):
    def test_cross_filter_equality_no_delayed_entry_and_unfiltered_exit(self):
        s = strategy()
        f = pd.DataFrame({'ema_fast': [9, 11, 12, 9, 11, 9, 11, 9, 11], 'ema_slow': [10]*9,
                          'adx': [30, 24.9, 40, 1, 25, 0, np.nan, 20, 50], 'volume': [1]*8+[0]})
        s.populate_entry_trend(f, {})
        s.populate_exit_trend(f, {})
        self.assertEqual([4], f.index[f.enter_long.eq(1)].tolist())
        self.assertEqual([3, 5, 7], f.index[f.exit_long.eq(1)].tolist())
        self.assertFalse((f.enter_long.eq(1) & f.exit_long.eq(1)).any())
        self.assertEqual('ema_adx_down_exit', f.loc[3, 'exit_tag'])

    def candles(self):
        x = np.arange(500, dtype=float)
        c = 100 + x/10 + np.sin(x/8)*5
        return pd.DataFrame({'open': c, 'high': c+2, 'low': c-2, 'close': c, 'volume': np.ones(500)})

    def test_indicators_match_talib_and_warmup(self):
        f = self.candles(); s = strategy(); s.populate_indicators(f, {})
        np.testing.assert_allclose(f.adx, talib.ADX(f.high, f.low, f.close, timeperiod=14), equal_nan=True)
        np.testing.assert_allclose(f.ema_fast, talib.EMA(f.close, timeperiod=20), equal_nan=True)
        np.testing.assert_allclose(f.ema_slow, talib.EMA(f.close, timeperiod=60), equal_nan=True)
        s.populate_entry_trend(f, {});self.assertFalse(f.enter_long.iloc[:59].eq(1).any())
        self.assertEqual(240, s.startup_candle_count)

    def test_future_changes_and_extension_preserve_past(self):
        s = strategy(); original = self.candles(); changed = original.copy()
        changed.loc[350:, ['open', 'high', 'low', 'close']] *= 2
        def calc(f):
            s.populate_indicators(f, {});s.populate_entry_trend(f, {});s.populate_exit_trend(f, {});return f
        a = calc(original);b = calc(changed);c = calc(self.candles().iloc[:350].copy())
        for key in ['ema_fast', 'ema_slow', 'adx', 'enter_long', 'exit_long']:
            np.testing.assert_allclose(a[key].iloc[:350], b[key].iloc[:350], equal_nan=True)
            np.testing.assert_allclose(a[key].iloc[:350], c[key], equal_nan=True)


if __name__ == '__main__':
    unittest.main()
