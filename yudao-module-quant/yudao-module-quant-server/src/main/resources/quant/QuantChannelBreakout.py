from pandas import DataFrame
from freqtrade.strategy import IStrategy


class QuantEmaBaseline(IStrategy):
    # Shared adapter entry point; strategy identity is the immutable platform version.
    INTERFACE_VERSION = 3
    timeframe = "1h"
    can_short = False
    startup_candle_count = 240
    minimal_roi = {"0": __ROI__}
    stoploss = -__STOP__
    process_only_new_candles = True
    use_exit_signal = True
    exit_profit_only = False
    ignore_roi_if_entry_signal = False

    def populate_indicators(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        # Exclude the current candle: only previously closed bars form the channel.
        dataframe["entry_high"] = dataframe["high"].rolling(__ENTRY__).max().shift(1)
        dataframe["exit_low"] = dataframe["low"].rolling(__EXIT__).min().shift(1)
        return dataframe

    def populate_entry_trend(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        dataframe.loc[(dataframe["close"] > dataframe["entry_high"]) & (dataframe["volume"] > 0), "enter_long"] = 1
        return dataframe

    def populate_exit_trend(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        dataframe.loc[(dataframe["close"] < dataframe["exit_low"]) & (dataframe["volume"] > 0), "exit_long"] = 1
        return dataframe
