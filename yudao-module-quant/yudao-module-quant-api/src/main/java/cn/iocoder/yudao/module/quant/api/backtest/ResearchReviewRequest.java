package cn.iocoder.yudao.module.quant.api.backtest;
import jakarta.validation.constraints.*;
public record ResearchReviewRequest(
 @NotBlank @Pattern(regexp="ACCEPTED|REJECTED") String decision,
 @NotBlank @Size(max=500) String comment,
 @NotBlank @Pattern(regexp="[0-9a-f]{64}") String evidenceHash) {}
