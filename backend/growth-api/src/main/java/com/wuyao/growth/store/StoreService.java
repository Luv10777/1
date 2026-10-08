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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StoreService {
    private final StoreRepository storeRepository;
    private final StoreMemberRepository memberRepository;
    private final AccountService accounts;
    private final StoreAccessService accessService;
    private final BrandService brandService;
    private final List<StoreClosureGuard> closureGuards;

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
        store.setProfile(extras(store.getProfile(), request.transportGuide(), request.amenities(), request.specialHours()));
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
        store.setProfile(extras(store.getProfile(), request.transportGuide(), request.amenities(), request.specialHours()));
        store.setUpdatedAt(Instant.now());
        try {
            return StoreDtos.StoreView.of(storeRepository.saveAndFlush(store));
        } catch (DataIntegrityViolationException ex) {
            throw BizException.of(ErrorCode.CONFLICT, "门店名称已存在");
        }
    }

    /**
     * 关店。关了的门店不再出现在任何地方，目前也没有恢复的入口，所以先让依赖它的模块把关：
     * 有直播正在进行、或者有声音样本只开放给这家店时不能关。门店名在关店后可以再用。
     */
    @Transactional
    public void archive(Long storeId, Long userId) {
        accounts.requireOwner(userId);
        Store store = accessService.requireAccess(storeId, userId);
        for (StoreClosureGuard guard : closureGuards) {
            guard.blocksClosing(storeId).ifPresent(reason -> { throw BizException.of(ErrorCode.CONFLICT, reason); });
        }
        store.setStatus("ARCHIVED");
        store.setUpdatedAt(Instant.now());
        storeRepository.saveAndFlush(store);
        closureGuards.forEach(guard -> guard.closed(storeId));
    }

    /** 供生成内容的模块使用：营业中门店的事实资料。只在当前商户内可见，不另做成员校验。 */
    @Transactional(readOnly = true)
    public Optional<StoreDtos.Profile> profile(Long storeId) {
        return storeRepository.findByIdAndStatus(storeId, "ACTIVE").map(StoreDtos.Profile::of);
    }

    /** 请求里没带的项（null）保持原样，带了的整项替换。 */
    private StoreDtos.Extras extras(StoreDtos.Extras current, String transportGuide, List<String> amenities,
                                    List<StoreDtos.SpecialHour> specialHours) {
        StoreDtos.Extras base = current == null ? StoreDtos.Extras.EMPTY : current;
        return new StoreDtos.Extras(
                transportGuide == null ? base.transportGuide() : trim(transportGuide),
                amenities == null ? base.amenities() : amenities.stream().map(String::trim).distinct().toList(),
                specialHours == null ? base.specialHours() : normalized(specialHours));
    }

    /** 每条安排只留下对它有意义的字段，并核对字段之间的搭配。 */
    private List<StoreDtos.SpecialHour> normalized(List<StoreDtos.SpecialHour> rules) {
        List<StoreDtos.SpecialHour> result = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            StoreDtos.SpecialHour rule = rules.get(i);
            String where = "特殊营业安排第 " + (i + 1) + " 条：";
            boolean weekly = "WEEKLY".equals(rule.scope());
            if (weekly && rule.weekday() == null) throw invalid(where + "请选择星期几");
            if (!weekly) {
                try {
                    LocalDate.parse(rule.date() == null ? "" : rule.date());
                } catch (DateTimeParseException e) {
                    throw invalid(where + "请选择有效的日期");
                }
            }
            boolean closed = Boolean.TRUE.equals(rule.closed());
            if (!closed && (trim(rule.opensAt()) == null || trim(rule.closesAt()) == null)) {
                throw invalid(where + "请填写这一天的营业时间，或改为休息");
            }
            result.add(new StoreDtos.SpecialHour(rule.scope(), weekly ? rule.weekday() : null, weekly ? null : rule.date(),
                    closed, closed ? null : rule.opensAt(), closed ? null : rule.closesAt(), trim(rule.note())));
        }
        return result;
    }

    private static BizException invalid(String message) {
        return BizException.of(ErrorCode.BAD_REQUEST, message);
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
