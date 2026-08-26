package com.wuyao.vimax.repository;

import com.wuyao.vimax.entity.VideoProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 视频项目 Repository
 */
@Repository
public interface VideoProjectRepository extends JpaRepository<VideoProject, Long> {

    /**
     * 根据项目代码查找
     */
    Optional<VideoProject> findByProjectCode(String projectCode);

    /**
     * 查询商家的项目列表
     */
    List<VideoProject> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);

    /**
     * 按状态查询
     */
    List<VideoProject> findByStatusOrderByCreatedAtDesc(String status);

    /**
     * 检查项目代码是否存在
     */
    boolean existsByProjectCode(String projectCode);
}
