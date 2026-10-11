package cn.iocoder.yudao.module.quant.controller.admin.backtest;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.quant.api.backtest.HistoricalSegmentsRequest;
import cn.iocoder.yudao.module.quant.service.HistoricalSegmentsService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/quant/backtest/segments")
public class HistoricalSegmentsController {
    private final HistoricalSegmentsService service;
    public HistoricalSegmentsController(HistoricalSegmentsService service) { this.service=service; }
    @PostMapping("/preview") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String,Object>> preview(@Valid @RequestBody HistoricalSegmentsRequest r)throws Exception{return success(service.preview(tenant(),owner(),r));}
    @PostMapping("/create") @PreAuthorize("@ss.hasPermission('quant:backtest:create')")
    public CommonResult<String> create(@Valid @RequestBody HistoricalSegmentsRequest r)throws Exception{return success(service.create(tenant(),owner(),r));}
    @GetMapping("/list") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<List<Map<String,Object>>> list(){return success(service.list(tenant(),owner()));}
    @GetMapping("/get") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String,Object>> get(@RequestParam String id){return success(service.get(tenant(),owner(),id));}
    private static long tenant(){return Objects.requireNonNull(TenantContextHolder.getTenantId());}
    private static long owner(){return Objects.requireNonNull(SecurityFrameworkUtils.getLoginUserId());}
    @ExceptionHandler(IllegalArgumentException.class)
    public CommonResult<Void> badRequest(IllegalArgumentException e){return CommonResult.error(400,e.getMessage());}
}
