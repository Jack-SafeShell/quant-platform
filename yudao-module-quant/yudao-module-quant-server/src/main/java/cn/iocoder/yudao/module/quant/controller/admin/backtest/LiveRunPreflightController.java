package cn.iocoder.yudao.module.quant.controller.admin.backtest;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.quant.service.LiveRunPreflightService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/quant/backtest")
public class LiveRunPreflightController {
    private final LiveRunPreflightService preflight;
    public LiveRunPreflightController(LiveRunPreflightService preflight){this.preflight=preflight;}
    public record Budget(BigDecimal orderNotional,BigDecimal maxSessionLoss,Integer feeBps,Integer slippageBps){}
    @PostMapping("/live-control/run-check")
    @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String,Object>> check(@RequestParam String reportId,@RequestBody Budget budget){return success(preflight.check(TenantContextHolder.getRequiredTenantId(),SecurityFrameworkUtils.getLoginUserId(),reportId,budget.orderNotional(),budget.maxSessionLoss(),budget.feeBps(),budget.slippageBps()));}
    @ExceptionHandler(IllegalArgumentException.class)
    public CommonResult<Void> badRequest(IllegalArgumentException e){return CommonResult.error(400,e.getMessage());}
}
