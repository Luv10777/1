package com.wuyao.growth.store;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.entity.User;
import com.wuyao.growth.iam.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StoreService {
    private final StoreRepository storeRepository;
    private final StoreMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final StoreAccessService accessService;

    @Transactional(readOnly = true)
    public List<StoreDtos.StoreView> list(Long userId) {
        return memberRepository.findAllByUserIdAndStatusOrderByStoreIdAsc(userId, "ACTIVE").stream()
                .map(member -> storeRepository.findByIdAndStatus(member.getStoreId(), "ACTIVE"))
                .flatMap(java.util.Optional::stream)
                .map(StoreDtos.StoreView::of)
                .toList();
    }

    @Transactional
    public StoreDtos.StoreView create(StoreDtos.CreateRequest request, Long userId) {
        Long tenantId = TenantContext.require();
        User user = userRepository.findById(userId)
                .filter(candidate -> tenantId.equals(candidate.getTenantId()))
                .orElseThrow(() -> BizException.of(ErrorCode.UNAUTHORIZED, "当前用户不存在"));
        Store store = new Store();
        store.setTenantId(tenantId);
        store.setName(request.name().trim());
        store.setAddress(trim(request.address()));
        store.setPhone(trim(request.phone()));
        store.setBusinessHours(trim(request.businessHours()));
        store.setCreatedBy(user.getId());
        try {
            store = storeRepository.save(store);
            StoreMember member = new StoreMember();
            member.setTenantId(tenantId);
            member.setStoreId(store.getId());
            member.setUserId(userId);
            member.setRole("OWNER");
            memberRepository.save(member);
            return StoreDtos.StoreView.of(store);
        } catch (DataIntegrityViolationException ex) {
            throw BizException.of(ErrorCode.CONFLICT, "门店名称已存在");
        }
    }

    @Transactional(readOnly = true)
    public StoreDtos.StoreView get(Long storeId, Long userId) {
        return StoreDtos.StoreView.of(accessService.requireAccess(storeId, userId));
    }

    @Transactional
    public StoreDtos.StoreView update(Long storeId, StoreDtos.UpdateRequest request, Long userId) {
        Store store = accessService.requireAccess(storeId, userId);
        if (request.version() != null && !request.version().equals(store.getVersion())) {
            throw BizException.of(ErrorCode.CONFLICT, "门店已被其他人更新，请刷新后重试");
        }
        store.setName(request.name().trim());
        store.setAddress(trim(request.address()));
        store.setPhone(trim(request.phone()));
        store.setBusinessHours(trim(request.businessHours()));
        store.setUpdatedAt(Instant.now());
        try {
            return StoreDtos.StoreView.of(storeRepository.saveAndFlush(store));
        } catch (DataIntegrityViolationException ex) {
            throw BizException.of(ErrorCode.CONFLICT, "门店名称已存在");
        }
    }

    @Transactional
    public void archive(Long storeId, Long userId) {
        Store store = accessService.requireAccess(storeId, userId);
        store.setStatus("ARCHIVED");
        store.setUpdatedAt(Instant.now());
        storeRepository.save(store);
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
