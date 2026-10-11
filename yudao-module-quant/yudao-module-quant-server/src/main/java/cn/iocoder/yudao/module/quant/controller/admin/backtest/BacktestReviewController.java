package cn.iocoder.yudao.module.quant.controller.admin.backtest;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.quant.service.BacktestReviewService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/quant/backtest")
public class BacktestReviewController {
    private final BacktestReviewService service;
    public BacktestReviewController(BacktestReviewService service) { this.service = service; }
    @GetMapping("/review")
    @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String,Object>> review(@RequestParam String id) {
        return success(service.get(TenantContextHolder.getRequiredTenantId(), SecurityFrameworkUtils.getLoginUserId(), id));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public CommonResult<Void> badRequest(IllegalArgumentException e) { return CommonResult.error(400, e.getMessage()); }
}
