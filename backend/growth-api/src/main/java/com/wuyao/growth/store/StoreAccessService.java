package com.wuyao.growth.store;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoreAccessService {
    private final StoreRepository storeRepository;
    private final StoreMemberRepository memberRepository;

    @Transactional(readOnly = true)
    public Store requireAccess(Long storeId, Long userId) {
        Store store = storeRepository.findByIdAndStatus(storeId, "ACTIVE")
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "门店不存在或已停用"));
        memberRepository.findByStoreIdAndUserIdAndStatus(storeId, userId, "ACTIVE")
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN, "没有访问该门店的权限"));
        return store;
    }

    public Long requireTenant() {
        return TenantContext.require();
    }
}
