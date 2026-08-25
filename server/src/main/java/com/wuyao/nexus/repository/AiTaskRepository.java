package com.wuyao.nexus.repository;

import com.wuyao.nexus.entity.AiTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiTaskRepository extends JpaRepository<AiTask, Long> {
    Page<AiTask> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    Optional<AiTask> findByIdAndTenantId(Long id, Long tenantId);

    Optional<AiTask> findByCode(String code);

    @Query("SELECT t FROM AiTask t WHERE t.status IN :statuses ORDER BY t.priority DESC, t.createdAt ASC")
    List<AiTask> findPendingTasks(@Param("statuses") List<AiTask.TaskStatus> statuses, Pageable pageable);

    @Query("SELECT t FROM AiTask t WHERE t.callbackStatus = 'PENDING' AND t.status = 'COMPLETED'")
    List<AiTask> findTasksNeedingCallback(Pageable pageable);

    long countByTenantIdAndStatus(Long tenantId, AiTask.TaskStatus status);
}
