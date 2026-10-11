"""Execute inside the pinned Freqtrade image, without network or credentials."""
from pathlib import Path
import unittest
import pandas as pd
import talib

source = Path('/workspace/yudao-module-quant/yudao-module-quant-server/src/main/resources/quant/QuantRsiReversion.py').read_text()
for name, value in {'PERIOD': '14', 'ENTRY': '30', 'EXIT': '70', 'ROI': '0.06', 'STOP': '0.03'}.items():
    source = source.replace('__' + name + '__', value)
namespace = {'__name__': 'rsi_test'}
exec(compile(source, 'rsi_strategy', 'exec'), namespace)
strategy = namespace['QuantEmaBaseline']({})


class RsiBehaviorTest(unittest.TestCase):
    def signals(self, frame):
        result = strategy.populate_entry_trend(frame.copy(), {})
        return strategy.populate_exit_trend(result, {})

    def test_recovery_threshold_equality_volume_and_collision(self):
        frame = pd.DataFrame({'rsi': [float('nan'), 25, 30, 31, 35, 70, 71, 75, 20, 80, 20, 31],
                              'volume': [1]*11 + [0]})
        result = self.signals(frame)
        self.assertEqual([3], result.index[result.enter_long.eq(1)].tolist())
        self.assertEqual([6, 9], result.index[result.exit_long.eq(1)].tolist())
        self.assertEqual('rsi_recovery', result.loc[3, 'enter_tag'])
        self.assertEqual('rsi_upper_exit', result.loc[6, 'exit_tag'])
        self.assertFalse((result.enter_long.eq(1) & result.exit_long.eq(1)).any())
        # Equality, remaining above a line, insufficient history, and zero volume do not retrigger.

    def frame(self):
        closes = [100-i*0.2 for i in range(90)] + [82+i*0.4 for i in range(90)] + [118-i*0.3 for i in range(90)]
        return pd.DataFrame({'open': closes, 'high': closes, 'low': closes, 'close': closes, 'volume': [1]*len(closes)})

    def test_indicator_matches_pinned_talib_and_requires_history(self):
        frame = self.frame()
        result = strategy.populate_indicators(frame.copy(), {})
        pd.testing.assert_series_equal(result.rsi, talib.RSI(frame.close, timeperiod=14), check_names=False)
        self.assertTrue(result.rsi.iloc[:14].isna().all())
        self.assertGreater(len(self.signals(result).index[self.signals(result).enter_long.eq(1)]), 0)

    def test_future_candles_do_not_change_past_indicator_or_signals(self):
        frame = self.frame()
        original = self.signals(strategy.populate_indicators(frame.copy(), {}))
        frame.loc[150:, ['open','high','low','close']] = 1000
        changed = self.signals(strategy.populate_indicators(frame.copy(), {}))
        pd.testing.assert_frame_equal(original.iloc[:150], changed.iloc[:150])


if __name__ == '__main__':
    unittest.main()
