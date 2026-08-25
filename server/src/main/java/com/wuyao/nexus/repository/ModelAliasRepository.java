package com.wuyao.nexus.repository;

import com.wuyao.nexus.entity.ModelAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelAliasRepository extends JpaRepository<ModelAlias, Long> {
    Optional<ModelAlias> findByAliasAndStatus(String alias, ModelAlias.ModelStatus status);
    List<ModelAlias> findByTypeAndStatusOrderByPriorityDesc(ModelAlias.ModelType type, ModelAlias.ModelStatus status);
}
