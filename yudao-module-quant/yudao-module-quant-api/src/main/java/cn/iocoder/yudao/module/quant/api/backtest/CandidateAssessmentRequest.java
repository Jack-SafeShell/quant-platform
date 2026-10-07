package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.*;
import java.util.List;

public record CandidateAssessmentRequest(
        @NotNull @Size(min=2,max=4) List<@NotBlank @Pattern(regexp="[0-9a-f-]{36}") String> experimentIds,
        @NotNull @Min(0) @Max(100) Integer feeBps,
        @NotNull @Min(0) @Max(100) Integer slippageBps,
        @NotNull @Min(0) @Max(100) Integer stressFeeBps,
        @NotNull @Min(0) @Max(100) Integer stressSlippageBps) {}
