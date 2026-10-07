package com.wuyao.growth.store;

import com.wuyao.growth.brand.BrandService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.service.AccountService;
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
    private final AccountService accounts;
    private final StoreAccessService accessService;
    private final BrandService brandService;

    /** 管理员看到本商户的全部门店，店员只看到分配给他的。 */
    @Transactional(readOnly = true)
    public List<StoreDtos.StoreView> list(Long userId) {
        if (accounts.isOwner(userId)) {
            return storeRepository.findAllByStatusOrderByIdAsc("ACTIVE").stream().map(StoreDtos.StoreView::of).toList();
        }
        return memberRepository.findAllByUserIdAndStatusOrderByStoreIdAsc(userId, "ACTIVE").stream()
                .map(member -> storeRepository.findByIdAndStatus(member.getStoreId(), "ACTIVE"))
                .flatMap(java.util.Optional::stream)
                .map(StoreDtos.StoreView::of)
                .toList();
    }

    @Transactional
    public StoreDtos.StoreView create(StoreDtos.CreateRequest request, Long userId) {
        Long tenantId = TenantContext.require();
        accounts.requireOwner(userId);
        Store store = new Store();
        store.setTenantId(tenantId);
        store.setName(request.name().trim());
        store.setAddress(trim(request.address()));
        store.setPhone(trim(request.phone()));
        store.setBusinessHours(trim(request.businessHours()));
        store.setCreatedBy(userId);
        // 未指定品牌时归到默认品牌；商户还没有品牌时保持为空。
        store.setBrandId(brandService.resolveForStore(request.brandId()));
        try {
            // 管理员自动能进新门店，不需要登记成员；店员由管理员在员工管理里分配。
            return StoreDtos.StoreView.of(storeRepository.saveAndFlush(store));
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
        // 不带 brandId 的保存不改变归属，旧客户端不会把门店的品牌清掉。
        // 先确认品牌再改门店：确认品牌会查库，不能让它把还没校验的门店改动提前写出去。
        Long brandId = store.getBrandId();
        if (request.brandId() != null && !request.brandId().equals(brandId)) {
            // 店员可以改本店的地址、电话这些资料，但门店归哪个品牌由管理员决定。
            accounts.requireOwner(userId);
            brandId = brandService.resolveForStore(request.brandId());
        }
        store.setName(request.name().trim());
        store.setAddress(trim(request.address()));
        store.setPhone(trim(request.phone()));
        store.setBusinessHours(trim(request.businessHours()));
        store.setBrandId(brandId);
        store.setUpdatedAt(Instant.now());
        try {
            return StoreDtos.StoreView.of(storeRepository.saveAndFlush(store));
        } catch (DataIntegrityViolationException ex) {
            throw BizException.of(ErrorCode.CONFLICT, "门店名称已存在");
        }
    }

    @Transactional
    public void archive(Long storeId, Long userId) {
        accounts.requireOwner(userId);
        Store store = accessService.requireAccess(storeId, userId);
        store.setStatus("ARCHIVED");
        store.setUpdatedAt(Instant.now());
        storeRepository.save(store);
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
