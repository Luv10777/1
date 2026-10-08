package com.wuyao.growth.live;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LiveSessionQaRepository extends JpaRepository<LiveSessionQa, Long> {

    List<LiveSessionQa> findBySessionIdOrderByIdAsc(Long sessionId);
}
