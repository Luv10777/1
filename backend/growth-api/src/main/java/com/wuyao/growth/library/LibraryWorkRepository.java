package com.wuyao.growth.library;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LibraryWorkRepository extends JpaRepository<LibraryWork, Long> {
    /** folderId 为空时取根目录；all 为真时不分文件夹。kind 为空时图片视频都要。 */
    @Query(value = "select w from LibraryWork w where w.ownerId = :ownerId "
            + "and (:all = true or (:folderId is null and w.folderId is null) or w.folderId = :folderId) "
            + "and (:kind is null or w.kind = :kind) order by w.savedAt desc, w.id desc",
            countQuery = "select count(w) from LibraryWork w where w.ownerId = :ownerId "
            + "and (:all = true or (:folderId is null and w.folderId is null) or w.folderId = :folderId) "
            + "and (:kind is null or w.kind = :kind)")
    Page<LibraryWork> page(@Param("ownerId") Long ownerId, @Param("folderId") Long folderId, @Param("all") boolean all,
                           @Param("kind") String kind, Pageable pageable);

    Optional<LibraryWork> findByKindAndSourceId(String kind, Long sourceId);

    Optional<LibraryWork> findByIdAndOwnerId(Long id, Long ownerId);

    List<LibraryWork> findByOwnerIdAndIdIn(Long ownerId, Collection<Long> ids);

    List<LibraryWork> findByOwnerIdAndKindAndSourceIdIn(Long ownerId, String kind, Collection<Long> sourceIds);

    @Query("select w.folderId, count(w) from LibraryWork w where w.ownerId = :ownerId group by w.folderId")
    List<Object[]> countByFolder(@Param("ownerId") Long ownerId);

    @Modifying
    @Query("delete from LibraryWork w where w.folderId = :folderId")
    int deleteByFolderId(@Param("folderId") Long folderId);
}
