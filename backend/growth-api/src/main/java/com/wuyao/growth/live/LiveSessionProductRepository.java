package com.wuyao.growth.live;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LiveSessionProductRepository extends JpaRepository<LiveSessionProduct, Long> {

    List<LiveSessionProduct> findBySessionIdOrderBySortOrderAscIdAsc(Long sessionId);

    void deleteBySessionId(Long sessionId);
}
