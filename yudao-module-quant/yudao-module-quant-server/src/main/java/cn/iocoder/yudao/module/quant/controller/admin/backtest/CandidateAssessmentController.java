package cn.iocoder.yudao.module.quant.controller.admin.backtest;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.quant.api.backtest.CandidateAssessmentRequest;
import cn.iocoder.yudao.module.quant.service.CandidateAssessmentService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/quant/backtest/strategy-experiment")
public class CandidateAssessmentController {
    private final CandidateAssessmentService service;
    public CandidateAssessmentController(CandidateAssessmentService service){this.service=service;}
    @PostMapping("/assessment") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String,Object>> assess(@Valid @RequestBody CandidateAssessmentRequest request){return CommonResult.success(service.assess(TenantContextHolder.getRequiredTenantId(),SecurityFrameworkUtils.getLoginUserId(),request));}
    @ExceptionHandler(IllegalArgumentException.class) public CommonResult<Void> bad(IllegalArgumentException e){return CommonResult.error(400,e.getMessage());}
}
