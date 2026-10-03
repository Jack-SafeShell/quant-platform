package cn.iocoder.yudao.module.quant.api.backtest;
import jakarta.validation.constraints.*;
public record ExchangeAccountRequest(
    @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String id,
    @NotBlank @Pattern(regexp="okx|binance") String exchange,
    @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}\\.dpapi") String credentialFileName) {}
