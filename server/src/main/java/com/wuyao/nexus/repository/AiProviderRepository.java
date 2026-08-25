package com.wuyao.nexus.repository;

import com.wuyao.nexus.entity.AiProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiProviderRepository extends JpaRepository<AiProvider, Long> {
    Optional<AiProvider> findByCode(String code);
    List<AiProvider> findByTypeAndStatus(AiProvider.ProviderType type, AiProvider.ProviderStatus status);
}
