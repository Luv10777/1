package com.wuyao.growth.creative.image;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface ImageItemRepository extends JpaRepository<ImageItem,Long> {
 List<ImageItem> findByCreationIdOrderByOrdinal(Long id);
 Page<ImageItem> findByStatusAndOutputKeyIsNotNullOrderByIdDesc(String status, Pageable page);
 @Query(value="select c.reference_hash, i.image_hash from image_items i "
   +"join image_creations c on c.id=i.creation_id where i.tenant_id=:tenantId "
   +"and i.id<>:itemId and i.status='SUCCEEDED' and i.image_hash is not null "
   +"and c.reference_hash is not null and c.request->>'workflow'='POSTER' "
   +"and c.request->>'ratio'=:ratio "
   +"and (c.parent_id is null or c.variation is not null) order by i.id desc limit 30",nativeQuery=true)
 List<Object[]> recentPosterFingerprints(Long tenantId,Long itemId,String ratio);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select i from ImageItem i where i.id=:id")
 Optional<ImageItem> lock(Long id);
}
