from pandas import DataFrame
import talib.abstract as ta
from freqtrade.strategy import IStrategy


class QuantEmaBaseline(IStrategy):
    """Fixed research baseline, not a live-trading recommendation."""
    INTERFACE_VERSION = 3
    timeframe = "1h"
    can_short = False
    startup_candle_count = 240
    minimal_roi = {"0": 0.04}
    stoploss = -0.02
    process_only_new_candles = True

    def populate_indicators(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        dataframe["ema20"] = ta.EMA(dataframe, timeperiod=20)
        dataframe["ema60"] = ta.EMA(dataframe, timeperiod=60)
        return dataframe

    def populate_entry_trend(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        dataframe.loc[(dataframe["ema20"] > dataframe["ema60"]) &
                      (dataframe["ema20"].shift(1) <= dataframe["ema60"].shift(1)) &
                      (dataframe["volume"] > 0), "enter_long"] = 1
        return dataframe

    def populate_exit_trend(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        dataframe.loc[(dataframe["ema20"] < dataframe["ema60"]) &
                      (dataframe["ema20"].shift(1) >= dataframe["ema60"].shift(1)) &
                      (dataframe["volume"] > 0), "exit_long"] = 1
        return dataframe
