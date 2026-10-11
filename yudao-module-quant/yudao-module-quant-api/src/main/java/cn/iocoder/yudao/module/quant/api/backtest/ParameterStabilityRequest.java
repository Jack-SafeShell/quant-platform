package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.*;

public record ParameterStabilityRequest(
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String requestKey,
        @NotBlank @Pattern(regexp="[0-9a-f-]{36}") String strategyVersionId,
        @NotBlank @Pattern(regexp="[0-9a-f-]{36}") String parameterSetId,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String datasetId,
        @NotBlank @Pattern(regexp="\\d{4}-\\d{2}-\\d{2}") String startDate,
        @NotBlank @Pattern(regexp="\\d{4}-\\d{2}-\\d{2}") String endDate,
        @NotBlank @Pattern(regexp="fastPeriod|slowPeriod|entryPeriod|exitPeriod|rsiPeriod|entryThreshold|exitThreshold") String parameter,
        @Min(1) @Max(10) int step) { }
