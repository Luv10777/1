package com.wuyao.growth.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductFaqRepository extends JpaRepository<ProductFaq, Long> {

    List<ProductFaq> findByProductIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(Long productId);

    Optional<ProductFaq> findByIdAndProductIdAndDeletedAtIsNull(Long id, Long productId);
}
