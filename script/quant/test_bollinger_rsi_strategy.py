"""Run inside the pinned Freqtrade image; deterministic signals, no network or credentials."""
from pathlib import Path
import unittest
import pandas as pd
import numpy as np
import talib

def strategy(band=20, deviation=2, period=14, ceiling=50):
    source=Path('/workspace/yudao-module-quant/yudao-module-quant-server/src/main/resources/quant/QuantBollingerRsi.py').read_text()
    for name,value in {'BAND_PERIOD':band,'DEVIATION':deviation,'RSI_PERIOD':period,'CEILING':ceiling,'ROI':0.06,'STOP':0.03}.items():
        source=source.replace('__'+name+'__',str(value))
    scope={'__name__':'bollinger_test'}
    exec(compile(source,'bollinger_strategy','exec'),scope)
    return scope['QuantEmaBaseline']({})

def signals(bot, frame):
    return bot.populate_exit_trend(bot.populate_entry_trend(frame.copy(),{}),{})

class BollingerRsiBehaviorTest(unittest.TestCase):
    def frame(self):
        close=100+np.sin(np.arange(500)*0.17)*5+np.cos(np.arange(500)*0.43)*2
        return pd.DataFrame({'open':close,'high':close+1,'low':close-1,'close':close,'volume':1})

    def test_recovery_requires_rising_rsi_and_disallows_collision(self):
        # Each pair starts outside the lower band. Cases: valid, RSI falling,
        # RSI too high, exactly at ceiling, at middle, zero volume, flat bands.
        close=[89,91]*7
        close[9]=100
        rsi=[30,35,40,39,50,51,49,50,30,35,30,35,30,35]
        frame=pd.DataFrame({'close':close,'bb_lower':90,'bb_middle':100,'bb_upper':110,'rsi':rsi,'volume':1})
        frame.loc[11,'volume']=0;frame.loc[13,'bb_upper']=90
        result=signals(strategy(),frame)
        self.assertEqual([1,7],result.index[result.enter_long.eq(1)].tolist())
        self.assertEqual([9],result.index[result.exit_long.eq(1)].tolist())
        self.assertFalse((result.enter_long.eq(1)&result.exit_long.eq(1)).any())
        self.assertEqual('bb_middle_exit',result.loc[9,'exit_tag'])

    def test_lower_band_equality_and_missing_indicators(self):
        frame=pd.DataFrame({'close':[90,91,90,90,89,91], 'bb_lower':[90,90,90,90,90,float('nan')], 'bb_middle':100,'bb_upper':110,'rsi':[30,35,30,35,30,35],'volume':1})
        result=signals(strategy(),frame)
        self.assertEqual([1],result.index[result.enter_long.eq(1)].tolist())
        flat=self.frame();flat.loc[:,'close']=100
        self.assertFalse(signals(strategy(),strategy().populate_indicators(flat,{})).enter_long.eq(1).any())

    def test_indicator_uses_close_sma_population_deviation_and_talib_rsi(self):
        frame=self.frame();result=strategy().populate_indicators(frame.copy(),{})
        upper,middle,lower=talib.BBANDS(frame.close,timeperiod=20,nbdevup=2,nbdevdn=2,matype=0)
        for column,expected in [('bb_upper',upper),('bb_middle',middle),('bb_lower',lower),('rsi',talib.RSI(frame.close,timeperiod=14))]:
            pd.testing.assert_series_equal(result[column],expected,check_names=False)
        np.testing.assert_allclose(result.bb_lower.iloc[19:],(frame.close.rolling(20).mean()-2*frame.close.rolling(20).std(ddof=0)).iloc[19:],atol=1e-8)
        self.assertTrue(result.bb_lower.iloc[:19].isna().all());self.assertTrue(result.rsi.iloc[:14].isna().all())

    def test_future_changes_and_later_extension_cannot_change_past(self):
        frame=self.frame();modified=frame.copy();modified.loc[350:,'close']*=3
        for args in [(20,2,14,50),(100,4,50,70),(5,1,2,10)]:
            bot=strategy(*args)
            original=signals(bot,bot.populate_indicators(frame.copy(),{}))
            changed=signals(bot,bot.populate_indicators(modified.copy(),{}))
            prefix=signals(bot,bot.populate_indicators(frame.iloc[:350].copy(),{}))
            pd.testing.assert_frame_equal(original.iloc[:350],changed.iloc[:350])
            pd.testing.assert_frame_equal(original.iloc[:350],prefix)
            self.assertFalse(original.enter_long.iloc[:max(args[0]-1,args[2])+1].eq(1).any())

if __name__=='__main__':unittest.main()
