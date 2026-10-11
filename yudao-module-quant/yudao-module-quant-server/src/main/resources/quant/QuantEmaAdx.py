from pandas import DataFrame
import talib.abstract as ta
from freqtrade.strategy import IStrategy


class QuantEmaBaseline(IStrategy):
    """Historical EMA crosses filtered by ADX on the cross candle only."""
    INTERFACE_VERSION = 3
    timeframe = "1h"
    can_short = False
    startup_candle_count = 240
    minimal_roi = {"0": __ROI__}
    stoploss = -__STOP__
    process_only_new_candles = True

    def populate_indicators(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        dataframe["ema_fast"] = ta.EMA(dataframe, timeperiod=__FAST__)
        dataframe["ema_slow"] = ta.EMA(dataframe, timeperiod=__SLOW__)
        dataframe["adx"] = ta.ADX(dataframe, timeperiod=__ADX_PERIOD__)
        return dataframe

    def populate_entry_trend(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        dataframe.loc[(dataframe["ema_fast"] > dataframe["ema_slow"]) &
                      (dataframe["ema_fast"].shift(1) <= dataframe["ema_slow"].shift(1)) &
                      (dataframe["adx"] >= __ADX_THRESHOLD__) &
                      (dataframe["volume"] > 0), ["enter_long", "enter_tag"]] = (1, "ema_adx_entry")
        return dataframe

    def populate_exit_trend(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        dataframe.loc[(dataframe["ema_fast"] < dataframe["ema_slow"]) &
                      (dataframe["ema_fast"].shift(1) >= dataframe["ema_slow"].shift(1)) &
                      (dataframe["volume"] > 0), ["exit_long", "exit_tag"]] = (1, "ema_adx_down_exit")
        return dataframe
