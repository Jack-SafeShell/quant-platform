"""Run with the project's pinned Freqtrade image; no network or credentials required."""
from pathlib import Path
import unittest
import pandas as pd

source = Path("/workspace/yudao-module-quant/yudao-module-quant-server/src/main/resources/quant/QuantChannelBreakout.py").read_text()
for name, value in {"ENTRY": "2", "EXIT": "2", "ROI": "0.06", "STOP": "0.03"}.items():
    source = source.replace("__" + name + "__", value)
namespace = {"__name__": "breakout_test"}
exec(compile(source, "breakout_strategy", "exec"), namespace)
strategy = namespace["QuantEmaBaseline"]({})

class BreakoutBehaviorTest(unittest.TestCase):
    def frame(self):
        return pd.DataFrame({"high": [10, 11, 99, 12, 12, 10], "low": [8, 9, 7, 8, 8, 6],
                             "close": [9, 10, 12, 7, 7, 5], "volume": [1, 1, 1, 1, 0, 1]})
    def signals(self, frame):
        frame = strategy.populate_indicators(frame.copy(), {})
        frame = strategy.populate_entry_trend(frame, {})
        return strategy.populate_exit_trend(frame, {})
    def test_prior_channel_and_volume(self):
        result = self.signals(self.frame())
        self.assertEqual(11, result.loc[2, "entry_high"])
        self.assertEqual(1, result.loc[2, "enter_long"])
        self.assertEqual(7, result.loc[3, "exit_low"])
        self.assertTrue(pd.isna(result.loc[3, "exit_long"]))  # equality is not a breakout
        self.assertTrue(pd.isna(result.loc[4, "exit_long"]))  # zero-volume bar
        self.assertEqual(1, result.loc[5, "exit_long"])
        self.assertTrue(pd.isna(result.loc[1, "entry_high"]))  # insufficient history
    def test_future_changes_do_not_change_past(self):
        frame = self.frame()
        original = self.signals(frame)
        frame.loc[4:, ["high", "low", "close", "volume"]] = [1000, 0, 1000, 20]
        changed = self.signals(frame)
        pd.testing.assert_frame_equal(original.iloc[:4], changed.iloc[:4])

if __name__ == "__main__":
    unittest.main()
