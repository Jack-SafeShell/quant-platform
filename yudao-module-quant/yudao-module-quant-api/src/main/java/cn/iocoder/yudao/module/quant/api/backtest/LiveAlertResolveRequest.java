package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.*;

public record LiveAlertResolveRequest(@NotBlank @Size(max=36) String checkId,
                                      @NotBlank @Pattern(regexp="[a-f0-9]{64}") String evidenceHash,
                                      @NotBlank @Size(max=500) String comment) {}
