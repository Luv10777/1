package com.wuyao.growth.team;

import com.wuyao.growth.iam.dto.AccountDtos;
import com.wuyao.growth.iam.service.AccountService;
import com.wuyao.growth.store.StoreMembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 员工管理：管理员添加店员、决定他能进哪些门店、停用或移除他。
 * 账号本身归 iam，门店范围归 store，这里只把两边的动作放进同一个事务。
 */
@Service
@RequiredArgsConstructor
public class TeamService {

    private final AccountService accounts;
    private final StoreMembershipService memberships;

    @Transactional(readOnly = true)
    public List<TeamDtos.MemberView> list(Long currentUserId) {
        accounts.requireOwner(currentUserId);
        Map<Long, List<Long>> stores = memberships.storeIdsByUser();
        return accounts.members().stream()
                .map(account -> TeamDtos.MemberView.of(account, stores.getOrDefault(account.id(), List.of()), currentUserId))
                .toList();
    }

    @Transactional
    public TeamDtos.MemberView add(TeamDtos.CreateRequest request, Long currentUserId) {
        accounts.requireOwner(currentUserId);
        AccountDtos.Account staff = accounts.createStaff(request.phone(), request.name());
        return TeamDtos.MemberView.of(staff, memberships.replace(staff.id(), request.storeIds()), currentUserId);
    }

    @Transactional
    public TeamDtos.MemberView update(Long memberId, TeamDtos.UpdateRequest request, Long currentUserId) {
        accounts.requireOwner(currentUserId);
        AccountDtos.Account staff = accounts.renameStaff(memberId, request.name());
        return TeamDtos.MemberView.of(staff, memberships.replace(memberId, request.storeIds()), currentUserId);
    }

    @Transactional
    public TeamDtos.MemberView setEnabled(Long memberId, boolean enabled, Long currentUserId) {
        accounts.requireOwner(currentUserId);
        AccountDtos.Account staff = accounts.setStaffEnabled(memberId, enabled);
        return TeamDtos.MemberView.of(staff, memberships.storeIdsByUser().getOrDefault(memberId, List.of()), currentUserId);
    }

    @Transactional
    public void remove(Long memberId, Long currentUserId) {
        accounts.requireOwner(currentUserId);
        accounts.removeStaff(memberId);
        memberships.removeAll(memberId);
    }
}
