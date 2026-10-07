package com.wuyao.growth.store;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 谁能进哪家门店，全系统只在这里判断：管理员能进本商户的全部门店，店员只能进分配给他的门店。
 * 商品、知识库、直播、声音都通过 {@link #requireAccess} 把关，不要各自另写一套。
 */
@Service
@RequiredArgsConstructor
public class StoreAccessService {
    private final StoreRepository storeRepository;
    private final StoreMemberRepository memberRepository;
    private final AccountService accounts;

    @Transactional(readOnly = true)
    public Store requireAccess(Long storeId, Long userId) {
        Store store = storeRepository.findByIdAndStatus(storeId, "ACTIVE")
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "门店不存在或已停用"));
        if (!allowed(storeId, userId)) throw BizException.of(ErrorCode.FORBIDDEN, "没有访问该门店的权限");
        return store;
    }

    /** 与 {@link #requireAccess} 同一条规则，只回答是或否；门店不存在或已停用时为否。 */
    @Transactional(readOnly = true)
    public boolean hasAccess(Long storeId, Long userId) {
        return storeRepository.findByIdAndStatus(storeId, "ACTIVE").isPresent() && allowed(storeId, userId);
    }

    public Long requireTenant() {
        return TenantContext.require();
    }

    private boolean allowed(Long storeId, Long userId) {
        // roleOf 会拒绝已停用或不属于当前商户的账号，名单上还留着他也没用。
        return AccountService.OWNER.equals(accounts.roleOf(userId))
                || memberRepository.findByStoreIdAndUserIdAndStatus(storeId, userId, "ACTIVE").isPresent();
    }
}
