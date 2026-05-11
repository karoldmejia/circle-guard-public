package com.circleguard.identity.repository;

import com.circleguard.identity.model.IdentityMapping;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@Transactional
class IdentityMappingRepositoryTest {

    @Autowired
    private IdentityMappingRepository repository;

    @Test
    void shouldSaveAndRetrieveWithAutomaticEncryption() {
        IdentityMapping mapping = IdentityMapping.builder()
                .realIdentity("test-user")
                .anonymousId(UUID.randomUUID())
                .identityHash("hash123")
                .salt("salt123")
                .build();
        
        IdentityMapping saved = repository.save(mapping);
        assertNotNull(saved);
        assertNotNull(saved.getAnonymousId());
        
        // Clear persistence context to force fetch from DB
        repository.flush();
        
        IdentityMapping found = repository.findById(saved.getAnonymousId()).orElseThrow();
        assertEquals("test-user", found.getRealIdentity());
    }

    @Test
    void shouldFindMappingByIdentityHash() {
        String realIdentity = "user@example.com";
        String hash = "hash123";
        String salt = "salt456";
        
        IdentityMapping mapping = IdentityMapping.builder()
                .realIdentity(realIdentity)
                .anonymousId(UUID.randomUUID())
                .identityHash(hash)
                .salt(salt)
                .build();
        IdentityMapping saved = repository.save(mapping);
        repository.flush();
        
        assertNotNull(saved.getAnonymousId());
        Optional<IdentityMapping> found = repository.findByIdentityHash(hash);
        assertTrue(found.isPresent());
        assertEquals(realIdentity, found.get().getRealIdentity());
    }
}
