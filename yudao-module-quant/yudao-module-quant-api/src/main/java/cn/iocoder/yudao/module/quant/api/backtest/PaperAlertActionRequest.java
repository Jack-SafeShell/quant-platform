package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PaperAlertActionRequest(
        @NotBlank @Pattern(regexp = "ACKNOWLEDGE|RESOLVE") String action,
        @NotBlank @Size(max = 500) String comment) {
}
