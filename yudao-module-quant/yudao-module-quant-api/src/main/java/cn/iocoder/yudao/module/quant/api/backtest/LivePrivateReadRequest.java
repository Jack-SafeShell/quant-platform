package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LivePrivateReadRequest(
        @NotBlank @Pattern(regexp = "CONFIRM_OKX_PRIVATE_READ") String confirmation,
        @NotBlank @Size(max = 500) String comment) {}
