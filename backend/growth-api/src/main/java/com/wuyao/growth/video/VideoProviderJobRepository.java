package com.wuyao.growth.video;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VideoProviderJobRepository extends JpaRepository<VideoProviderJob, Long> {
    Optional<VideoProviderJob> findByWorkflowId(Long workflowId);
}
