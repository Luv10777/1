package com.wuyao.growth.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // Optional filters need an explicit JDBC string type even when their values are null.
    // Otherwise Hibernate/PostgreSQL can infer bytea for concat/lower on an unfiltered list.
    @Query("""
            select p from Product p
            where p.storeId = :storeId and p.deletedAt is null
              and (cast(:keyword as string) is null
                   or lower(p.name) like lower(concat('%', cast(:keyword as string), '%'))
                   or lower(p.category) like lower(concat('%', cast(:keyword as string), '%'))
                   or cast(p.price as string) like concat('%', cast(:keyword as string), '%'))
              and (cast(:type as string) is null or p.type = cast(:type as string))
              and (cast(:category as string) is null or p.category = cast(:category as string))
            order by p.updatedAt desc, p.id desc
            """)
    Page<Product> search(@Param("storeId") Long storeId,
                         @Param("keyword") String keyword,
                         @Param("type") String type,
                         @Param("category") String category,
                         Pageable pageable);

    /** 公开商品墙：全平台已上架的实物商品。可见性同时由 products 的公开行级策略兜底。 */
    @Query("""
            select p from Product p
            where p.status = 'ACTIVE' and p.onlineStatus = 'PUBLISHED' and p.deletedAt is null
              and p.type = 'PHYSICAL'
              and (cast(:keyword as string) is null
                   or lower(p.name) like lower(concat('%', cast(:keyword as string), '%'))
                   or lower(p.category) like lower(concat('%', cast(:keyword as string), '%')))
            order by p.updatedAt desc, p.id desc
            """)
    Page<Product> searchPublicPhysical(@Param("keyword") String keyword, Pageable pageable);

    /** 每家门店最近更新的前 limit 件在售实物商品：id, 该门店在售总数。条件同 {@link #searchPublicPhysical}。 */
    @Query(value = """
            select id, total from (
                select p.id, p.store_id, p.updated_at,
                       count(*) over (partition by p.store_id) as total,
                       row_number() over (partition by p.store_id order by p.updated_at desc, p.id desc) as seq
                from products p
                where p.store_id in (:storeIds)
                  and p.status = 'ACTIVE' and p.online_status = 'PUBLISHED' and p.deleted_at is null
                  and p.type = 'PHYSICAL'
            ) listed
            where seq <= :limit
            order by store_id, seq
            """, nativeQuery = true)
    List<Object[]> findPublicShowcase(@Param("storeIds") java.util.Collection<Long> storeIds, @Param("limit") int limit);

    Optional<Product> findByIdAndDeletedAtIsNull(Long id);

    List<Product> findAllByStoreIdAndStatusAndOnlineStatusAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc(
            Long storeId, String status, String onlineStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id and p.status = 'ACTIVE' and p.onlineStatus = 'PUBLISHED' and p.deletedAt is null")
    Optional<Product> findForSaleUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id and p.deletedAt is null")
    Optional<Product> findForUpdate(@Param("id") Long id);

    /** 商户关闭时把所有在线商品撤回草稿，对外的店铺页和商品图随之不可见。 */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.onlineStatus = 'DRAFT', p.updatedAt = :now where p.tenantId = :tenantId and p.onlineStatus = 'PUBLISHED'")
    int withdrawAll(@Param("tenantId") Long tenantId, @Param("now") Instant now);
}
