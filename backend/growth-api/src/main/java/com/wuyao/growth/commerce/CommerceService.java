package com.wuyao.growth.commerce;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.product.ProductDtos;
import com.wuyao.growth.product.ProductService;
import com.wuyao.growth.store.StoreAccessService;
import com.wuyao.growth.store.StoreService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CommerceService {
    private final ProductService products;
    private final OrderWriteService writer;
    private final SalesOrderRepository orders;
    private final SalesOrderItemRepository items;
    private final StoreAccessService storeAccess;
    private final StoreService stores;
    @PersistenceContext private EntityManager entityManager;

    public CommerceDtos.Receipt place(CommerceDtos.PlaceOrderRequest request) {
        Map<Long, Integer> requested = new LinkedHashMap<>();
        for (var item : request.items()) requested.merge(item.productId(), item.quantity(), Integer::sum);
        if (requested.size() > 20 || requested.values().stream().anyMatch(quantity -> quantity > 99)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "单笔订单最多 20 种商品，每种最多 99 件");
        }
        List<ProductService.SaleProduct> listed = products.saleProducts(requested.keySet().stream().toList());
        if (listed.size() != requested.size()) throw BizException.of(ErrorCode.NOT_FOUND, "部分商品已下架或暂不可售");
        Long storeId = listed.get(0).storeId();
        Long tenantId = listed.get(0).tenantId();
        if (listed.stream().anyMatch(item -> !storeId.equals(item.storeId()) || !tenantId.equals(item.tenantId()))) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "一次下单只能购买同一家店铺的商品");
        }
        return TenantContext.runAs(tenantId, () -> writer.place(storeId, request, requested, tenantId));
    }

    /** 全平台商品墙。门店已关闭的商品查不到店名，直接略过，不展示一个打不开的店铺。 */
    @Transactional(readOnly = true)
    public PageResult<CommerceDtos.CatalogItem> publicCatalog(int page, int size, String keyword) {
        PageResult<ProductDtos.PublicListing> listed = products.publicCatalog(page, size, keyword);
        Map<Long, String> names = stores.publicNames(listed.items().stream().map(ProductDtos.PublicListing::storeId).distinct().toList());
        List<CommerceDtos.CatalogItem> items = listed.items().stream().filter(item -> names.containsKey(item.storeId()))
                .map(item -> CommerceDtos.CatalogItem.of(item, names.get(item.storeId()))).toList();
        return new PageResult<>(items, listed.page(), listed.size(), listed.total(), listed.totalPages());
    }

    @Transactional(readOnly = true)
    public CommerceDtos.Receipt publicReceipt(String token) {
        entityManager.createNativeQuery("SELECT set_config('app.order_lookup_token', :token, true)")
                .setParameter("token", token).getSingleResult();
        SalesOrder order = orders.findByLookupToken(token)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "订单凭证无效或已过期"));
        return CommerceDtos.Receipt.of(order, items.findAllByOrderIdOrderByIdAsc(order.getId()), false);
    }

    @Transactional(readOnly = true)
    public List<CommerceDtos.MerchantOrder> merchantOrders(Long storeId, Long userId) {
        storeAccess.requireAccess(storeId, userId);
        return orders.findAllByStoreIdOrderByCreatedAtDescIdDesc(storeId).stream()
                .map(order -> CommerceDtos.MerchantOrder.of(order, items.findAllByOrderIdOrderByIdAsc(order.getId())))
                .toList();
    }

    public CommerceDtos.MerchantOrder changeStatus(Long orderId, Long storeId, Long userId,
                                                    CommerceDtos.StatusRequest request) {
        storeAccess.requireAccess(storeId, userId);
        return writer.changeStatus(orderId, request, storeId);
    }
}
