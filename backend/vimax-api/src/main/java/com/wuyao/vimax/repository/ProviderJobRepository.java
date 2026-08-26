package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.ProviderJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 供应商任务 Repository
 */
@Repository
public interface ProviderJobRepository extends JpaRepository<ProviderJob, Long> {

    /**
     * 根据供应商和任务 ID 查找
     */
    Optional<ProviderJob> findByProviderAndProviderJobId(String provider, String providerJobId);

    /**
     * 查询待检查的任务（SUBMITTED, QUEUED, PROCESSING）
     */
    @Query("SELECT j FROM ProviderJob j WHERE j.status IN ('SUBMITTED', 'QUEUED', 'PROCESSING') " +
           "ORDER BY j.submittedAt ASC")
    List<ProviderJob> findPendingJobs();

    /**
     * 查询生成任务的所有 Provider 任务
     */
    List<ProviderJob> findByGenerationTaskIdOrderBySubmittedAtAsc(Long generationTaskId);

    /**
     * 按状态查询
     */
    List<ProviderJob> findByStatus(String status);
}
