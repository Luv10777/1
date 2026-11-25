package com.wuyao.growth.library;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkFolderRepository extends JpaRepository<WorkFolder, Long> {
    List<WorkFolder> findByOwnerIdOrderByCreatedAtAscIdAsc(Long ownerId);

    Optional<WorkFolder> findByIdAndOwnerId(Long id, Long ownerId);

    boolean existsByOwnerIdAndName(Long ownerId, String name);

    boolean existsByOwnerIdAndNameAndIdNot(Long ownerId, String name, Long id);

    long countByOwnerId(Long ownerId);

    /** 同一账号的作品库写操作先拿这把事务级锁，「名字没被占用」「作品还没保存」这类先查后写的判断才不会被并发打破。 */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('works-library:' || CAST(:tenantId AS text) || ':' || CAST(:ownerId AS text), 0))",
            nativeQuery = true)
    int lockOwner(@Param("tenantId") Long tenantId, @Param("ownerId") Long ownerId);
}
