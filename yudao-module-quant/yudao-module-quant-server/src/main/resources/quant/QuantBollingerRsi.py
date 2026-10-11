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
        bands = ta.BBANDS(dataframe, timeperiod=__BAND_PERIOD__, nbdevup=float(__DEVIATION__), nbdevdn=float(__DEVIATION__), matype=0)
        dataframe["bb_lower"] = bands["lowerband"]
        dataframe["bb_middle"] = bands["middleband"]
        dataframe["bb_upper"] = bands["upperband"]
        dataframe["rsi"] = ta.RSI(dataframe, timeperiod=__RSI_PERIOD__)
        return dataframe

    def populate_entry_trend(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        # Recovery into the band, below the middle; RSI must improve, not merely stay low.
        dataframe.loc[
            (dataframe["close"].shift(1) <= dataframe["bb_lower"].shift(1))
            & (dataframe["close"] > dataframe["bb_lower"])
            & (dataframe["close"] < dataframe["bb_middle"])
            & (dataframe["rsi"] > dataframe["rsi"].shift(1))
            & (dataframe["rsi"] <= __CEILING__)
            & (dataframe["bb_upper"] > dataframe["bb_lower"])
            & (dataframe["volume"] > 0),
            ["enter_long", "enter_tag"],
        ] = (1, "bb_rsi_recovery")
        return dataframe

    def populate_exit_trend(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        dataframe.loc[
            (dataframe["close"] >= dataframe["bb_middle"]) & (dataframe["volume"] > 0),
            ["exit_long", "exit_tag"],
        ] = (1, "bb_middle_exit")
        return dataframe
