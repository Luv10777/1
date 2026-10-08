package com.wuyao.growth.live;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LiveKnowledgeSnapshotRepository extends JpaRepository<LiveKnowledgeSnapshot, Long> {

    List<LiveKnowledgeSnapshot> findBySessionIdOrderByPriorityAscIdAsc(Long sessionId);

    void deleteBySessionId(Long sessionId);
}
