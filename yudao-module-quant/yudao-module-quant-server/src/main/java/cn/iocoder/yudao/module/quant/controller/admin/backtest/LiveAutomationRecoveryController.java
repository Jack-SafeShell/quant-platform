package cn.iocoder.yudao.module.quant.controller.admin.backtest;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.quant.api.backtest.LiveAlertResolveRequest;
import cn.iocoder.yudao.module.quant.service.LiveAutomationRecoveryService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/quant/backtest/live-control/automation")
public class LiveAutomationRecoveryController {
    private final LiveAutomationRecoveryService recovery;
    public LiveAutomationRecoveryController(LiveAutomationRecoveryService recovery){this.recovery=recovery;}
    @PostMapping("/alert/check") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String,Object>> check(@RequestParam String alertId){return success(recovery.check(tenant(),owner(),alertId));}
    @PostMapping("/alert/resolve") @PreAuthorize("@ss.hasPermission('quant:backtest:create')")
    public CommonResult<Map<String,Object>> resolve(@RequestParam String alertId,@Valid @RequestBody LiveAlertResolveRequest request){return success(recovery.resolve(tenant(),owner(),alertId,request));}
    @GetMapping("/alert-actions") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<List<Map<String,Object>>> actions(@RequestParam String sessionId){return success(recovery.list(tenant(),owner(),sessionId));}
    @ExceptionHandler(IllegalArgumentException.class) public CommonResult<Void> bad(IllegalArgumentException e){return CommonResult.error(400,e.getMessage());}
    private static long tenant(){return TenantContextHolder.getRequiredTenantId();}private static long owner(){return SecurityFrameworkUtils.getLoginUserId();}
}
