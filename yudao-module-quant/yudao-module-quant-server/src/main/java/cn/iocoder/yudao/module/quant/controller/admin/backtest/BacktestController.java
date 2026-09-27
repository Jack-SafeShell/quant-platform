package cn.iocoder.yudao.module.quant.controller.admin.backtest;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.quant.api.backtest.BacktestRequest;
import cn.iocoder.yudao.module.quant.api.backtest.ParameterSetRequest;
import cn.iocoder.yudao.module.quant.api.backtest.DatasetDownloadRequest;
import cn.iocoder.yudao.module.quant.service.BacktestService;
import cn.iocoder.yudao.module.quant.service.DatasetDownloadService;
import cn.iocoder.yudao.module.quant.framework.QuantProperties;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 量化历史回测")
@RestController
@RequestMapping("/quant/backtest")
public class BacktestController {
    private final BacktestService service;
    private final QuantProperties properties;
    private final DatasetDownloadService downloads;
    public BacktestController(BacktestService service, QuantProperties properties, DatasetDownloadService downloads) { this.service = service; this.properties = properties; this.downloads = downloads; }
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
    private static long tenant() { return Objects.requireNonNull(TenantContextHolder.getTenantId(), "租户上下文缺失"); }
    private static long owner() { return Objects.requireNonNull(SecurityFrameworkUtils.getLoginUserId(), "用户上下文缺失"); }
    @ExceptionHandler(IllegalArgumentException.class)
    public CommonResult<Void> badRequest(IllegalArgumentException e) { return CommonResult.error(400, e.getMessage()); }
}
