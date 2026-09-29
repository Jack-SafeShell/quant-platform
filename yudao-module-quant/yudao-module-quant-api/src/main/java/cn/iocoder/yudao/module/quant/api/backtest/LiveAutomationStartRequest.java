package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LiveAutomationStartRequest(@NotBlank String confirmation,
                                         @NotBlank @Size(max = 500) String comment) {
    public LiveAutomationStartRequest {
        confirmation = confirmation == null ? null : confirmation.trim();
        comment = comment == null ? null : comment.trim();
    }
}
