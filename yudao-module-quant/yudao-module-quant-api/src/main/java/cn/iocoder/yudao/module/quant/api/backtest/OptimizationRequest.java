package cn.iocoder.yudao.module.quant.api.backtest;
import jakarta.validation.constraints.*;
import java.util.List;
public record OptimizationRequest(
 @NotBlank @Pattern(regexp="[0-9a-f-]{36}") String strategyVersionId,
 @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String datasetId,
 @NotBlank @Pattern(regexp="\\d{4}-\\d{2}-\\d{2}") String trainStart,
 @NotBlank @Pattern(regexp="\\d{4}-\\d{2}-\\d{2}") String splitDate,
 @NotBlank @Pattern(regexp="\\d{4}-\\d{2}-\\d{2}") String validationEnd,
 @NotNull @Size(min=2,max=5) List<@Pattern(regexp="[0-9a-f-]{36}") String> parameterSetIds) {}
