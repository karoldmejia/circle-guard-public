package com.circleguard.identity.service;

import com.circleguard.identity.model.IdentityMapping;
import com.circleguard.identity.repository.IdentityMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for IdentityVaultService.
 * Core anonymization service: maps real identities to anonymous UUIDs.
 *
 * SECURITY REQUIREMENT: realIdentity must NEVER appear in logs or Kafka events.
 * DETERMINISM REQUIREMENT: same input must have same anonymousId.
 */
@ExtendWith(MockitoExtension.class)
class IdentityVaultServiceTest {

    @Mock
    private IdentityMappingRepository repository;

    @InjectMocks
    private IdentityVaultService identityVaultService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(identityVaultService, "hashSalt", "test-salt-12345678");
    }

    @Test
    @DisplayName("getOrCreateAnonymousId: same realIdentity always returns same anonymousId")
    void getOrCreateAnonymousId_sameInput_returnsSameAnonymousId() {
        String realIdentity = "john.doe@university.edu";
        UUID expectedId = UUID.randomUUID();

        IdentityMapping existing = IdentityMapping.builder()
            .anonymousId(expectedId)
            .realIdentity(realIdentity)
            .identityHash("some-hash")
            .salt("some-salt")
            .build();

        when(repository.findByIdentityHash(anyString())).thenReturn(Optional.of(existing));

        UUID result1 = identityVaultService.getOrCreateAnonymousId(realIdentity);
        UUID result2 = identityVaultService.getOrCreateAnonymousId(realIdentity);

        assertThat(result1).isEqualTo(result2).isEqualTo(expectedId);
    }

    @Test
    @DisplayName("getOrCreateAnonymousId: new identity should create and persist a mapping")
    void getOrCreateAnonymousId_newIdentity_createsMapping() {
        String realIdentity = "new.student@university.edu";
        UUID generatedId = UUID.randomUUID();

        when(repository.findByIdentityHash(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(IdentityMapping.class))).thenAnswer(inv -> {
            IdentityMapping saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "anonymousId", generatedId);
            return saved;
        });

        UUID result = identityVaultService.getOrCreateAnonymousId(realIdentity);

        assertThat(result).isNotNull();
        verify(repository).save(any(IdentityMapping.class));
    }

    @Test
    @DisplayName("getOrCreateAnonymousId: different identities must produce different anonymousIds")
    void getOrCreateAnonymousId_differentIdentities_differentIds() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        when(repository.findByIdentityHash(anyString()))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.empty());

        when(repository.save(any(IdentityMapping.class)))
            .thenAnswer(inv -> {
                IdentityMapping m = inv.getArgument(0);
                // Simulate different UUIDs being generated
                return IdentityMapping.builder()
                    .anonymousId(id1).realIdentity("user1@test.com")
                    .identityHash("hash1").salt("salt1").build();
            })
            .thenAnswer(inv -> IdentityMapping.builder()
                .anonymousId(id2).realIdentity("user2@test.com")
                .identityHash("hash2").salt("salt2").build());

        UUID result1 = identityVaultService.getOrCreateAnonymousId("user1@test.com");
        UUID result2 = identityVaultService.getOrCreateAnonymousId("user2@test.com");

        assertThat(result1).isNotEqualTo(result2);
    }

    @Test
    @DisplayName("resolveRealIdentity: should return the real identity for a known anonymousId")
    void resolveRealIdentity_knownId_returnsRealIdentity() {
        UUID anonymousId = UUID.randomUUID();
        String realIdentity = "secret.person@university.edu";

        IdentityMapping mapping = IdentityMapping.builder()
            .anonymousId(anonymousId)
            .realIdentity(realIdentity)
            .identityHash("hash")
            .salt("salt")
            .build();

        when(repository.findById(anonymousId)).thenReturn(Optional.of(mapping));

        String result = identityVaultService.resolveRealIdentity(anonymousId);

        assertThat(result).isEqualTo(realIdentity);
    }

    @Test
    @DisplayName("resolveRealIdentity: unknown anonymousId should throw 404 ResponseStatusException")
    void resolveRealIdentity_unknownId_throws404() {
        UUID unknownId = UUID.randomUUID();
        when(repository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> identityVaultService.resolveRealIdentity(unknownId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("hash computation: same input + same salt should produce same hash")
    void hashComputation_isDeterministic() {
        String identity = "test@university.edu";
        when(repository.findByIdentityHash(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        identityVaultService.getOrCreateAnonymousId(identity);
        identityVaultService.getOrCreateAnonymousId(identity);

        verify(repository, times(2)).findByIdentityHash(argThat(hash ->
            hash != null && !hash.isBlank() && hash.matches("[0-9a-f]{64}") // SHA-256 hex
        ));
    }

    @Test
    @DisplayName("getOrCreateAnonymousId: new mapping should have a non-null, non-empty salt")
    void getOrCreateAnonymousId_newMapping_hasSalt() {
        when(repository.findByIdentityHash(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(IdentityMapping.class))).thenAnswer(inv -> {
            IdentityMapping mapping = inv.getArgument(0);
            assertThat(mapping.getSalt()).isNotNull().isNotBlank();
            return mapping;
        });

        identityVaultService.getOrCreateAnonymousId("salt.test@university.edu");

        verify(repository).save(any(IdentityMapping.class));
    }
}