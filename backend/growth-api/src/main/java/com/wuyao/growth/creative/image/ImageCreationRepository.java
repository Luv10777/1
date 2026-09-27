package com.wuyao.growth.creative.image;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface ImageCreationRepository extends JpaRepository<ImageCreation,Long> {
 Optional<ImageCreation> findByRequestKey(String key);
 @Query(value="select * from image_creations where request->>'workflow' = :workflow order by id desc",
        countQuery="select count(*) from image_creations where request->>'workflow' = :workflow",nativeQuery=true)
 Page<ImageCreation> findByRequestWorkflowOrderByIdDesc(String workflow, Pageable page);
 @Query(value="select * from image_creations where request->>'workflow' = :workflow and parent_id is null order by id desc",
        countQuery="select count(*) from image_creations where request->>'workflow' = :workflow and parent_id is null",nativeQuery=true)
 Page<ImageCreation> findRootByRequestWorkflowOrderByIdDesc(String workflow, Pageable page);
 java.util.List<ImageCreation> findByParentIdOrderByIdDesc(Long parentId);
 @Query(value="select * from image_creations where tenant_id=:tenantId and id<:beforeId "
   +"and request->>'workflow'='POSTER' and plan is not null "
   +"and exists (select 1 from image_items i where i.creation_id=image_creations.id and i.status='SUCCEEDED') "
   +"and (parent_id is null or variation is not null) order by id desc limit 30",nativeQuery=true)
 java.util.List<ImageCreation> recentPosterPlans(Long tenantId,Long beforeId);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from ImageCreation c where c.id=:id")
 Optional<ImageCreation> lock(Long id);
 @Modifying
 @Query("update ImageCreation c set c.concurrencyPermitHeld=false where c.id=:id and c.concurrencyPermitHeld=true")
 int clearConcurrencyPermit(Long id);
 @Query(value="select true from pg_advisory_xact_lock(hashtextextended(:key, 0))",nativeQuery=true)
 boolean lockRequest(String key);
}
