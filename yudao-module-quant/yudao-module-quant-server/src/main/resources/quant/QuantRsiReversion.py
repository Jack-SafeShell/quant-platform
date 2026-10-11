from pandas import DataFrame
import talib.abstract as ta
from freqtrade.strategy import IStrategy


class QuantEmaBaseline(IStrategy):
    # Shared historical adapter class; immutable platform version identifies the rules.
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
        dataframe["rsi"] = ta.RSI(dataframe, timeperiod=__PERIOD__)
        return dataframe

    def populate_entry_trend(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        # Recovery above the lower line; all inputs are current/past closed candles.
        dataframe.loc[
            (dataframe["rsi"].shift(1) <= __ENTRY__) & (dataframe["rsi"] > __ENTRY__)
            & (dataframe["rsi"] <= __EXIT__) & (dataframe["volume"] > 0),
            ["enter_long", "enter_tag"],
        ] = (1, "rsi_recovery")
        return dataframe

    def populate_exit_trend(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        dataframe.loc[
            (dataframe["rsi"].shift(1) <= __EXIT__) & (dataframe["rsi"] > __EXIT__)
            & (dataframe["volume"] > 0),
            ["exit_long", "exit_tag"],
        ] = (1, "rsi_upper_exit")
        return dataframe
