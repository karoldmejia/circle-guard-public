package com.circleguard.identity.repository;

import com.circleguard.identity.model.IdentityMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.Optional;

public interface IdentityMappingRepository extends JpaRepository<IdentityMapping, UUID> {
    Optional<IdentityMapping> findByIdentityHash(String identityHash);
}
