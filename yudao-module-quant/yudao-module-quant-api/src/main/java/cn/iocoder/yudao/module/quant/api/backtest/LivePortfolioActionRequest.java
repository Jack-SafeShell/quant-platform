package cn.iocoder.yudao.module.quant.api.backtest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record LivePortfolioActionRequest(@NotBlank String confirmation,@NotBlank @Size(max=400) String comment) {}
