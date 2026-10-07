package com.wuyao.growth.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {
    List<Store> findAllByStatusOrderByIdAsc(String status);
    Optional<Store> findByIdAndStatus(Long id, String status);
}
