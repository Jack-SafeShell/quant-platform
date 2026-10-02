package cn.iocoder.yudao.module.quant.api.backtest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.math.BigDecimal;

public record LiveAutomationStartRequest(@NotBlank String confirmation,
                                         @NotBlank @Size(max = 500) String comment,
                                         @DecimalMin("0.01") @Digits(integer=12,fraction=8) BigDecimal orderNotional,
                                         @DecimalMin("0.01") @Digits(integer=12,fraction=8) BigDecimal maxSessionLoss,
                                         @Min(0) @Max(100) Integer feeBps,
                                         @Min(0) @Max(100) Integer slippageBps) {
    public LiveAutomationStartRequest(String confirmation, String comment) { this(confirmation, comment, null, null, null, null); }
    public LiveAutomationStartRequest {
        confirmation = confirmation == null ? null : confirmation.trim();
        comment = comment == null ? null : comment.trim();
    }
}
