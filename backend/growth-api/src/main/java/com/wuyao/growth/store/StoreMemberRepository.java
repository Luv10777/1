package com.wuyao.growth.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoreMemberRepository extends JpaRepository<StoreMember, Long> {
    List<StoreMember> findAllByUserIdAndStatusOrderByStoreIdAsc(Long userId, String status);
    Optional<StoreMember> findByStoreIdAndUserIdAndStatus(Long storeId, Long userId, String status);
    List<StoreMember> findAllByStatusOrderByStoreIdAsc(String status);
    List<StoreMember> findAllByUserId(Long userId);
}
