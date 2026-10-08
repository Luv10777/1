package com.wuyao.growth.team;

import com.wuyao.growth.iam.dto.AccountDtos;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class TeamDtos {

    private TeamDtos() {
    }

    public record CreateRequest(
            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,
            @NotBlank(message = "姓名不能为空") @Size(max = 80) String name,
            @NotNull(message = "请选择门店") @Size(max = 500) List<@NotNull @Positive Long> storeIds) {
    }

    public record UpdateRequest(
            @NotBlank(message = "姓名不能为空") @Size(max = 80) String name,
            @NotNull(message = "请选择门店") @Size(max = 500) List<@NotNull @Positive Long> storeIds) {
    }

    /**
     * @param storeIds 店员能进的门店；管理员能进全部门店，这里为空，由 allStores 表示
     * @param self     这一行是不是当前登录的人
     */
    public record MemberView(Long id, String name, String phone, String role, String status,
                             boolean allStores, List<Long> storeIds, boolean self,
                             Instant lastLoginAt, Instant createdAt) {

        static MemberView of(AccountDtos.Account account, List<Long> storeIds, Long currentUserId) {
            return new MemberView(account.id(), account.name(), account.phone(), account.role(), account.status(),
                    account.owner(), account.owner() ? List.of() : storeIds, account.id().equals(currentUserId),
                    account.lastLoginAt(), account.createdAt());
        }
    }
}
