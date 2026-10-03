package cn.iocoder.yudao.module.quant.controller.admin.backtest;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.quant.api.backtest.ExchangeAccountRequest;
import cn.iocoder.yudao.module.quant.service.ExchangeAccountService;
import cn.iocoder.yudao.module.quant.service.PublicMarketService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
@RestController
@RequestMapping("/quant/backtest")
public class ExchangeController {
    private final ExchangeAccountService accounts;private final PublicMarketService markets;
    private final cn.iocoder.yudao.module.quant.engine.BinancePrivateApiClient binance;
    public ExchangeController(ExchangeAccountService accounts,PublicMarketService markets,cn.iocoder.yudao.module.quant.engine.BinancePrivateApiClient binance){this.accounts=accounts;this.markets=markets;this.binance=binance;}
    @PostMapping("/exchange-account/binance-verify") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String,Object>> verifyBinance(){return success(binance.verifyReadOnly());}
    @GetMapping("/market/binance-rules") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String,Object>> binanceRules(){return success(binance.tradingRules());}
    @GetMapping("/exchange-account/current") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String,Object>> current(){return success(accounts.current());}
    @PostMapping("/exchange-account/register") @PreAuthorize("@ss.hasPermission('quant:backtest:create')")
    public CommonResult<String> register(@Valid @RequestBody ExchangeAccountRequest request){return success(accounts.register(request));}
    @GetMapping("/market/snapshot") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String,Object>> snapshot(@RequestParam String exchange){return success(markets.snapshot(exchange));}
}
