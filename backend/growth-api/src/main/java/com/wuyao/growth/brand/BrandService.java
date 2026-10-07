package com.wuyao.growth.brand;

import com.wuyao.growth.asset.AssetService;
import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 品牌档案属于商户：商户内的成员都能读取，只有管理员能新建、修改、删除和设置默认品牌。 */
@Service
@RequiredArgsConstructor
public class BrandService {

    private static final String ACTIVE = "ACTIVE";

    private final BrandRepository brands;
    private final BrandStoreLinks stores;
    private final AssetService assets;
    private final AccountService accounts;

    @Transactional(readOnly = true)
    public List<BrandDtos.View> list() {
        Map<Long, Long> counts = stores.activeStoreCounts();
        return brands.findAllByStatusOrderByDefaultBrandDescIdAsc(ACTIVE).stream()
                .map(brand -> BrandDtos.View.of(brand, counts.getOrDefault(brand.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public BrandDtos.View get(Long id) {
        return view(find(id));
    }

    /** 商户的第一个品牌自动成为默认品牌，已有的门店一并归到它名下。 */
    @Transactional
    public BrandDtos.View create(BrandDtos.CreateRequest req, Long userId) {
        Long tenantId = TenantContext.require();
        accounts.requireOwner(userId);
        brands.lockTenant(tenantId);
        String name = req.name().trim();
        if (brands.existsByNameAndStatus(name, ACTIVE)) {
            throw BizException.of(ErrorCode.CONFLICT, "品牌名已存在");
        }
        boolean first = brands.countByStatus(ACTIVE) == 0;
        Brand brand = new Brand();
        brand.setTenantId(tenantId);
        brand.setCreatedBy(userId);
        brand.setDefaultBrand(first);
        apply(brand, name, req);
        brand = brands.saveAndFlush(brand);
        if (first) stores.adoptUnassigned(brand.getId());
        return view(brand);
    }

    @Transactional
    public BrandDtos.View update(Long id, BrandDtos.UpdateRequest req, Long userId) {
        accounts.requireOwner(userId);
        brands.lockTenant(TenantContext.require());
        Brand brand = find(id);
        if (!req.version().equals(brand.getVersion())) {
            throw BizException.of(ErrorCode.CONFLICT, "品牌已被其他人更新，请刷新后重试");
        }
        String name = req.name().trim();
        if (brands.existsByNameAndStatusAndIdNot(name, ACTIVE, id)) {
            throw BizException.of(ErrorCode.CONFLICT, "品牌名已存在");
        }
        apply(brand, name, req);
        brand.setUpdatedAt(Instant.now());
        return view(brands.saveAndFlush(brand));
    }

    @Transactional
    public BrandDtos.View makeDefault(Long id, Long userId) {
        accounts.requireOwner(userId);
        brands.lockTenant(TenantContext.require());
        Brand brand = find(id);
        if (brand.isDefaultBrand()) return view(brand);
        // 唯一索引逐行检查，必须先让原来的默认品牌落库，再设置新的。
        brands.findFirstByDefaultBrandTrue().ifPresent(current -> {
            current.setDefaultBrand(false);
            current.setUpdatedAt(Instant.now());
            brands.saveAndFlush(current);
        });
        brand.setDefaultBrand(true);
        brand.setUpdatedAt(Instant.now());
        return view(brands.saveAndFlush(brand));
    }

    /** 归档而不是删除：已归档门店仍可能引用它。还有营业中的门店时不能归档。 */
    @Transactional
    public void archive(Long id, Long userId) {
        accounts.requireOwner(userId);
        brands.lockTenant(TenantContext.require());
        Brand brand = find(id);
        boolean only = brands.countByStatus(ACTIVE) == 1;
        long inUse = stores.activeStores(id);
        if (inUse > 0) {
            throw BizException.of(ErrorCode.CONFLICT, only
                    ? "还有 " + inUse + " 家门店属于这个品牌，而且它是唯一的品牌，不能删除。可以直接修改品牌资料"
                    : "还有 " + inUse + " 家门店属于这个品牌，请先把它们改到其他品牌");
        }
        if (brand.isDefaultBrand() && !only) {
            throw BizException.of(ErrorCode.CONFLICT, "请先把其他品牌设为默认品牌，再删除这个品牌");
        }
        brand.setStatus("ARCHIVED");
        brand.setDefaultBrand(false);
        brand.setUpdatedAt(Instant.now());
        brands.save(brand);
    }

    /**
     * 供门店模块使用，在门店的事务内调用：确认要挂的品牌可用并返回它的 ID。
     * 未指定时取默认品牌；商户还没有品牌时返回 null，门店暂不归属任何品牌。
     */
    @Transactional
    public Long resolveForStore(Long requestedBrandId) {
        brands.lockTenant(TenantContext.require());
        if (requestedBrandId == null) {
            return brands.findFirstByDefaultBrandTrue().map(Brand::getId).orElse(null);
        }
        return brands.findByIdAndStatus(requestedBrandId, ACTIVE).map(Brand::getId)
                .orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST, "所选品牌不存在或已删除"));
    }

    /** 供生成内容的模块使用：门店所属品牌的文字资料。门店没有品牌时为空。 */
    @Transactional(readOnly = true)
    public Optional<BrandDtos.Profile> profileForStore(Long storeId) {
        return stores.brandIdOf(storeId)
                .flatMap(id -> brands.findByIdAndStatus(id, ACTIVE))
                .map(BrandDtos.Profile::of);
    }

    private Brand find(Long id) {
        return brands.findByIdAndStatus(id, ACTIVE)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "品牌不存在或已删除"));
    }

    private BrandDtos.View view(Brand brand) {
        return BrandDtos.View.of(brand, stores.activeStores(brand.getId()));
    }

    private void apply(Brand brand, String name, BrandDtos.Fields req) {
        brand.setName(name);
        brand.setIndustry(text(req.industry()));
        brand.setSlogan(text(req.slogan()));
        brand.setIntro(text(req.intro()));
        brand.setWebsite(text(req.website()));
        brand.setPositioning(text(req.positioning()));
        brand.setTargetAudience(text(req.targetAudience()));
        brand.setLanguageStyle(text(req.languageStyle()));
        brand.setPrimaryColor(req.primaryColor() == null || req.primaryColor().isBlank()
                ? null : req.primaryColor().toLowerCase(java.util.Locale.ROOT));
        brand.setViGuidelines(text(req.viGuidelines()));
        brand.setLogoAssetId(image(req.logoAssetId(), brand.getLogoAssetId()));
        brand.setMiniProgramQrAssetId(image(req.miniProgramQrAssetId(), brand.getMiniProgramQrAssetId()));
        brand.setWechatQrAssetId(image(req.wechatQrAssetId(), brand.getWechatQrAssetId()));
        brand.setHistory(text(req.history()));
        brand.setBrandStory(text(req.brandStory()));
        brand.setCoreTeam(text(req.coreTeam()));
        brand.setCulture(text(req.culture()));
    }

    /** 只在换图时校验：必须是当前商户已上传完成的图片素材。 */
    private Long image(Long requested, Long current) {
        if (requested != null && !Objects.equals(requested, current)) assets.imageReference(requested);
        return requested;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
