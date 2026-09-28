package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LiveControlArmRequest(
        @NotBlank @Pattern(regexp = "CONFIRM_OFFLINE_GATE_ARM") String confirmation,
        @NotBlank @Size(max = 500) String comment) {}
