from pandas import DataFrame
from datetime import datetime, timedelta
from math import isfinite
import talib.abstract as ta
from freqtrade.strategy import IStrategy, timeframe_to_prev_date, timeframe_to_minutes, stoploss_from_absolute


class QuantEmaBaseline(IStrategy):
    # Shared adapter entry point; strategy identity is the immutable platform version.
    INTERFACE_VERSION = 3
    timeframe = "1h"
    can_short = False
    startup_candle_count = 240
    minimal_roi = {"0": __ROI__}
    stoploss = -__STOP__
    process_only_new_candles = True
    use_custom_stoploss = True
    trailing_stop = False
    position_adjustment_enable = False
    use_exit_signal = True
    exit_profit_only = False
    ignore_roi_if_entry_signal = False

    def populate_indicators(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        # Exclude the current candle: only previously closed bars form the channel.
        dataframe["entry_high"] = dataframe["high"].rolling(__ENTRY__).max().shift(1)
        dataframe["exit_low"] = dataframe["low"].rolling(__EXIT__).min().shift(1)
        dataframe["atr"] = ta.ATR(dataframe, timeperiod=__ATR_PERIOD__)
        return dataframe

    def populate_entry_trend(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        dataframe.loc[(dataframe["close"] > dataframe["entry_high"]) & (dataframe["volume"] > 0) &
                      (dataframe["atr"] > 0) & (dataframe["atr"] < float("inf")),
                      ["enter_long", "enter_tag"]] = (1, "breakout_atr_entry")
        return dataframe

    def populate_exit_trend(self, dataframe: DataFrame, metadata: dict) -> DataFrame:
        dataframe.loc[(dataframe["close"] < dataframe["exit_low"]) & (dataframe["volume"] > 0),
                      ["exit_long", "exit_tag"]] = (1, "breakout_atr_channel_exit")
        return dataframe

    def custom_stoploss(self, pair: str, trade, current_time: datetime,
                        current_rate: float, current_profit: float,
                        after_fill: bool, **kwargs) -> float | None:
        # Backtests may pass the current candle HIGH as current_rate. Only use
        # it to convert an already determined absolute price, never as an anchor.
        if trade.is_short or trade.leverage != 1 or not isfinite(current_rate) or current_rate <= 0:
            return None
        cutoff = timeframe_to_prev_date(self.timeframe, current_time)
        if not after_fill and cutoff <= timeframe_to_prev_date(self.timeframe, trade.open_date_utc):
            return None  # Keep the entry anchor throughout the entry candle.
        dataframe, _ = self.dp.get_analyzed_dataframe(pair, self.timeframe)
        if not {"date", "atr", "close"}.issubset(dataframe.columns):
            return None
        closed = dataframe.loc[dataframe["date"] < cutoff]
        if closed.empty:
            return None
        candle = closed.iloc[-1]
        if candle["date"] != cutoff - timedelta(minutes=timeframe_to_minutes(self.timeframe)):
            return None  # Missing/stale candle: retain the existing stop.
        atr, close = float(candle["atr"]), float(candle["close"])
        if not isfinite(atr) or atr <= 0 or not isfinite(close) or close <= 0:
            return None
        anchor = trade.open_rate if after_fill else close
        floor = trade.open_rate * (1 - __STOP__)
        price = max(floor, anchor - atr * __ATR_MULTIPLIER__)
        # Even after_fill cannot widen an already set stop. A stop at/above
        # current price cannot be represented by this callback; retain it.
        if price <= 0 or price >= current_rate or (trade.stop_loss and price <= trade.stop_loss):
            return None
        return stoploss_from_absolute(price, current_rate=current_rate, is_short=False, leverage=1)
