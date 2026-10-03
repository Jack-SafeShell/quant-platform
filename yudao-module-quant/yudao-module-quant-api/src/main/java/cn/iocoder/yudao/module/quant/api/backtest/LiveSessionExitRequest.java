package cn.iocoder.yudao.module.quant.api.backtest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
public record LiveSessionExitRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9-]{8,64}") String requestId,@NotBlank String confirmation,@NotBlank @Size(max=400) String comment) {}
