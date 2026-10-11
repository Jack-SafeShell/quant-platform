"""Pinned Freqtrade behavior checks; no network or credentials."""
import unittest
from pathlib import Path
from datetime import datetime, timezone
from types import SimpleNamespace
import numpy as np
import pandas as pd
import talib
from freqtrade.persistence import LocalTrade


def strategy():
    source = Path('/workspace/yudao-module-quant/yudao-module-quant-server/src/main/resources/quant/QuantBreakoutAtr.py').read_text()
    for key, value in {'ENTRY':'20', 'EXIT':'10', 'ATR_PERIOD':'14', 'ATR_MULTIPLIER':'2', 'ROI':'0.06', 'STOP':'0.03'}.items():
        source = source.replace('__'+key+'__', value)
    scope = {'__name__':'atr_test'}
    exec(compile(source, 'breakout_atr', 'exec'), scope)
    return scope['QuantEmaBaseline']({})


def dt(hour):
    return datetime(2026, 1, 1, hour, tzinfo=timezone.utc)


def setup_stop(frame, stop=97):
    s = strategy()
    s.dp = SimpleNamespace(get_analyzed_dataframe=lambda *_: (frame, None))
    t = SimpleNamespace(is_short=False, leverage=1, open_rate=100, stop_loss=stop, open_date_utc=dt(4))
    return s, t


class BreakoutAtrBehaviorTest(unittest.TestCase):
    def test_entry_anchor_cap_and_entry_bar_no_trailing(self):
        f = pd.DataFrame({'date':[dt(3),dt(4),dt(5)], 'atr':[1,0.1,0.1], 'close':[110,1000,1000]})
        s,t=setup_stop(f)
        self.assertAlmostEqual(.02,s.custom_stoploss('BTC/USDT',t,dt(4),100,0,True))
        self.assertIsNone(s.custom_stoploss('BTC/USDT',t,dt(4),150,0,False))
        f.loc[0,'atr']=10
        self.assertIsNone(s.custom_stoploss('BTC/USDT',t,dt(4),100,0,True)) # cap stays at 97

    def test_closed_price_anchor_independent_of_current_high_and_tightens_only(self):
        f=pd.DataFrame({'date':[dt(4),dt(5),dt(6)],'atr':[2,50,1],'close':[110,90,2000]})
        s,t=setup_stop(f,98)
        a=s.custom_stoploss('BTC/USDT',t,dt(5),120,0,False)
        b=s.custom_stoploss('BTC/USDT',t,dt(5),150,0,False)
        self.assertAlmostEqual(106,120*(1-a));self.assertAlmostEqual(106,150*(1-b))
        t.stop_loss=106
        self.assertIsNone(s.custom_stoploss('BTC/USDT',t,dt(6),120,0,False))
        self.assertIsNone(s.custom_stoploss('BTC/USDT',t,dt(5),100,0,False))
        # Missing a whole closed candle does not reuse an older observation.
        self.assertIsNone(s.custom_stoploss('BTC/USDT',t,dt(8),120,0,False))
        for invalid in [np.nan,np.inf,0,-1]:
            f.loc[0,'atr']=invalid
            self.assertIsNone(s.custom_stoploss('BTC/USDT',t,dt(5),120,0,False))

    def test_four_hour_boundary_and_engine_after_fill_cannot_widen(self):
        f=pd.DataFrame({'date':[dt(0),dt(4),dt(8)],'atr':[1,2,50],'close':[100,110,90]})
        s,_=setup_stop(f);s.timeframe='4h';s._ft_stop_uses_after_fill=True
        trade=LocalTrade(pair='BTC/USDT',open_rate=100,amount=1,fee_open=0,fee_close=0,leverage=1,is_short=False,open_date=dt(4))
        s.ft_stoploss_adjust(100,trade,dt(4),0,0,after_fill=True)
        self.assertAlmostEqual(98,trade.stop_loss)
        s.ft_stoploss_adjust(120,trade,dt(8),.2,0,low=107,high=120)
        self.assertAlmostEqual(106,trade.stop_loss)
        # Even explicit after_fill=True must not reset the tight stop to a lower value.
        s.ft_stoploss_adjust(120,trade,dt(12),.2,0,after_fill=True)
        self.assertAlmostEqual(106,trade.stop_loss)

    def candles(self):
        x=np.arange(500,dtype=float);c=100+x/10+np.sin(x/8)*5
        return pd.DataFrame({'date':pd.date_range('2026-01-01',periods=500,freq='h',tz='UTC'),'open':c,'high':c+2,'low':c-2,'close':c,'volume':np.ones(500)})

    def test_talib_channel_signals_and_future_data_causality(self):
        s=strategy();original=self.candles();changed=original.copy();changed.loc[350:,['open','high','low','close']]*=2
        def calc(f):
            s.populate_indicators(f,{});s.populate_entry_trend(f,{});s.populate_exit_trend(f,{});return f
        a=calc(original);b=calc(changed);c=calc(self.candles().iloc[:350].copy())
        np.testing.assert_allclose(a.atr,talib.ATR(a.high,a.low,a.close,timeperiod=14),equal_nan=True)
        np.testing.assert_allclose(a.entry_high,a.high.rolling(20).max().shift(1),equal_nan=True)
        for key in ['atr','entry_high','exit_low','enter_long','exit_long']:
            np.testing.assert_allclose(a[key].iloc[:350],b[key].iloc[:350],equal_nan=True)
            np.testing.assert_allclose(a[key].iloc[:350],c[key],equal_nan=True)
        f=pd.DataFrame({'close':[101,100,90,110,110],'entry_high':[100]*5,'exit_low':[95]*5,'volume':[1,1,1,0,1],'atr':[1,1,1,1,np.nan]})
        s.populate_entry_trend(f,{});s.populate_exit_trend(f,{})
        self.assertEqual([0],f.index[f.enter_long.eq(1)].tolist());self.assertEqual([2],f.index[f.exit_long.eq(1)].tolist())
        self.assertFalse(a.enter_long.iloc[:20].eq(1).any())


if __name__=='__main__':
    unittest.main()
