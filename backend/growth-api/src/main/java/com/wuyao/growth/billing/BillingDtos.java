package com.wuyao.growth.billing;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class BillingDtos {
    private BillingDtos() {}
    public record PlanSnapshot(Long id, String code, String name, String description, long priceMinor,
                               int durationDays, long giftCredits, boolean imageEnabled, boolean videoEnabled) {}
    public record Summary(String currencyName, int creditsPerYuan, int imageCredits, int videoAnalysisCredits, long available,
                          long giftAvailable, long purchasedAvailable, long held, boolean memberActive,
                          Instant memberUntil, PlanSnapshot plan, boolean canManage, boolean operator,
                          PaymentInstructions payment) {}
    public record PaymentInstructions(boolean available, String recipient, String account, String bank,
                                      String instructions, String qrCodeUrl) {}
    public record TopUp(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{8,80}") String requestKey,
                        @Min(100) @Max(10000000) long amountMinor) {}
    public record Subscribe(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{8,80}") String requestKey,
                            @NotNull @Positive Long planId) {}
    public record NewPlan(@NotBlank @Pattern(regexp="[A-Z0-9_-]{2,60}") String code,
                          @NotBlank @Size(max=100) String name, @NotNull @Size(max=600) String description,
                          @Min(1) @Max(100000000) long priceMinor, @Min(1) @Max(3660) int durationDays,
                          @Min(0) @Max(100000000) long giftCredits, boolean imageEnabled, boolean videoEnabled,
                          boolean published) {}
    public record Publication(boolean published) {}
    public record PlanListing(PlanSnapshot plan, boolean published) {}
    public record Confirmation(@Min(1) long receivedAmountMinor,
                               @NotBlank @Size(min=6,max=150) String receiptReference) {}
    public record LedgerView(Long id, String type, String description, long availableDelta, long heldDelta,
                             long availableAfter, long heldAfter, Instant createdAt) {}
    public record OrderView(String id, String kind, long amountMinor, long credits, String state,
                            PlanSnapshot plan, Instant createdAt, Instant expiresAt, Instant paidAt,
                            PaymentInstructions payment, String paymentMethod, String paymentCodeUrl) {}
    public record History<T>(List<T> items, boolean hasMore) {}
}
