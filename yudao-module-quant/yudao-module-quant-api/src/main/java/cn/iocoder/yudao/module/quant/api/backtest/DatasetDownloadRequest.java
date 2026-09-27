package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DatasetDownloadRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestKey,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String datasetId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String startDate,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String endDate) { }
