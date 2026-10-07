package com.wuyao.growth.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

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

    Optional<Product> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id and p.deletedAt is null")
    Optional<Product> findForUpdate(@Param("id") Long id);
}
