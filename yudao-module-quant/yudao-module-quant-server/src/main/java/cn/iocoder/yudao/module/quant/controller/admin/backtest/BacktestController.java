package cn.iocoder.yudao.module.quant.controller.admin.backtest;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.quant.api.backtest.BacktestRequest;
import cn.iocoder.yudao.module.quant.api.backtest.ParameterSetRequest;
import cn.iocoder.yudao.module.quant.api.backtest.DatasetDownloadRequest;
import cn.iocoder.yudao.module.quant.api.backtest.OptimizationRequest;
import cn.iocoder.yudao.module.quant.api.backtest.ResearchReviewRequest;
import cn.iocoder.yudao.module.quant.api.backtest.PaperAdmissionReviewRequest;
import cn.iocoder.yudao.module.quant.api.backtest.PaperSessionRequest;
import cn.iocoder.yudao.module.quant.api.backtest.PaperSessionReviewRequest;
import cn.iocoder.yudao.module.quant.service.BacktestService;
import cn.iocoder.yudao.module.quant.service.DatasetDownloadService;
import cn.iocoder.yudao.module.quant.service.OptimizationService;
import cn.iocoder.yudao.module.quant.service.PaperSessionService;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 量化历史回测")
@RestController
@RequestMapping("/quant/backtest")
public class BacktestController {
    private final BacktestService service;
    private final QuantProperties properties;
    private final DatasetDownloadService downloads;
    private final OptimizationService optimizations;
    private final PaperSessionService paperSessions;
    public BacktestController(BacktestService service, QuantProperties properties, DatasetDownloadService downloads, OptimizationService optimizations, PaperSessionService paperSessions) { this.service = service; this.properties = properties; this.downloads = downloads; this.optimizations=optimizations; this.paperSessions=paperSessions; }
    @PostMapping("/create")
    @PreAuthorize("@ss.hasPermission('quant:backtest:create')")
    public CommonResult<String> create(@Valid @RequestBody BacktestRequest request) throws Exception {
        return success(service.create(tenant(), owner(), request));
    }
    @GetMapping("/list")
    @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<List<Map<String, Object>>> list() { return success(service.list(tenant(), owner())); }
    @GetMapping("/get")
    @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String, Object>> get(@RequestParam String id) { return success(service.get(tenant(), owner(), id)); }
    @GetMapping("/report")
    @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public ResponseEntity<byte[]> report(@RequestParam String id, @RequestParam(defaultValue = "md") String format) throws Exception {
        var report = service.exportReport(tenant(), owner(), id, format);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(report.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(report.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(report.content());
    }
    @GetMapping("/capabilities")
    @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String, Object>> capabilities() {
        return success(Map.of("enabled", properties.isEnabled(), "strategy", "QuantEmaBaseline", "pair", "BTC/USDT", "timeframe", "1h", "tradingMode", "spot"));
    }
    @GetMapping("/strategy-versions")
    @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<List<Map<String, Object>>> strategyVersions() throws Exception {
        return success(service.listStrategyVersions(tenant(), owner()));
    }
    @GetMapping("/parameter-sets")
    @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<List<Map<String, Object>>> parameterSets() { return success(service.listParameterSets(tenant(), owner())); }
    @PostMapping("/parameter-set/create")
    @PreAuthorize("@ss.hasPermission('quant:backtest:create')")
    public CommonResult<String> createParameterSet(@Valid @RequestBody ParameterSetRequest request) {
        return success(service.createParameterSet(tenant(), owner(), request));
    }
    @PostMapping("/compare")
    @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<List<Map<String, Object>>> compare(@RequestBody List<String> ids) throws Exception {
        return success(service.compare(tenant(), owner(), ids));
    }
    @GetMapping("/datasets")
    @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<List<cn.iocoder.yudao.module.quant.engine.DatasetRegistry.DatasetQuality>> datasets() throws Exception {
        return success(service.listDatasets());
    }
    @PostMapping("/dataset-download/create") @PreAuthorize("@ss.hasPermission('quant:backtest:create')")
    public CommonResult<String> createDownload(@Valid @RequestBody DatasetDownloadRequest request){return success(downloads.create(tenant(),owner(),request));}
    @GetMapping("/dataset-download/list") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<List<Map<String,Object>>> listDownloads(){return success(downloads.list(tenant(),owner()));}
    @GetMapping("/dataset-download/get") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String,Object>> getDownload(@RequestParam String id){return success(downloads.get(tenant(),owner(),id));}
    @PostMapping("/optimization/create") @PreAuthorize("@ss.hasPermission('quant:backtest:create')")
    public CommonResult<String> createOptimization(@Valid @RequestBody OptimizationRequest request)throws Exception{return success(optimizations.create(tenant(),owner(),request));}
    @GetMapping("/optimization/list") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<List<Map<String,Object>>> listOptimizations(){return success(optimizations.list(tenant(),owner()));}
    @GetMapping("/optimization/get") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String,Object>> getOptimization(@RequestParam String id){return success(optimizations.get(tenant(),owner(),id));}
    @PostMapping("/optimization/review") @PreAuthorize("@ss.hasPermission('quant:backtest:create')")
    public CommonResult<String> reviewOptimization(@RequestParam String id,@Valid @RequestBody ResearchReviewRequest request){return success(optimizations.review(tenant(),owner(),id,request));}
    @PostMapping("/optimization/admission/review") @PreAuthorize("@ss.hasPermission('quant:backtest:create')")
    public CommonResult<String> reviewPaperAdmission(@RequestParam String id,@Valid @RequestBody PaperAdmissionReviewRequest request){return success(optimizations.reviewAdmission(tenant(),owner(),id,request));}
    @PostMapping("/paper-session/create") @PreAuthorize("@ss.hasPermission('quant:backtest:create')")
    public CommonResult<String> createPaperSession(@Valid @RequestBody PaperSessionRequest request){return success(paperSessions.create(tenant(),owner(),request));}
    @GetMapping("/paper-session/list") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<List<Map<String,Object>>> listPaperSessions(){return success(paperSessions.list(tenant(),owner()));}
    @GetMapping("/paper-session/get") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public CommonResult<Map<String,Object>> getPaperSession(@RequestParam String id){return success(paperSessions.get(tenant(),owner(),id));}
    @PostMapping("/paper-session/review") @PreAuthorize("@ss.hasPermission('quant:backtest:create')")
    public CommonResult<String> reviewPaperSession(@RequestParam String id,@Valid @RequestBody PaperSessionReviewRequest request){return success(paperSessions.review(tenant(),owner(),id,request));}
    @GetMapping("/optimization/research-report") @PreAuthorize("@ss.hasPermission('quant:backtest:query')")
    public ResponseEntity<byte[]> researchReport(@RequestParam String id){var report=optimizations.exportDraft(tenant(),owner(),id);return ResponseEntity.ok().contentType(MediaType.parseMediaType(report.contentType())).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(report.filename(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(report.content());}
    private static long tenant() { return Objects.requireNonNull(TenantContextHolder.getTenantId(), "租户上下文缺失"); }
    private static long owner() { return Objects.requireNonNull(SecurityFrameworkUtils.getLoginUserId(), "用户上下文缺失"); }
    @ExceptionHandler(IllegalArgumentException.class)
    public CommonResult<Void> badRequest(IllegalArgumentException e) { return CommonResult.error(400, e.getMessage()); }
}
