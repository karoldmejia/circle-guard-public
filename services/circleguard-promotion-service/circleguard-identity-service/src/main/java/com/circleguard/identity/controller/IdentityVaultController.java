package com.circleguard.identity.controller;

import com.circleguard.identity.service.IdentityVaultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.Map;
import org.springframework.kafka.core.KafkaTemplate;
import com.circleguard.identity.event.IdentityAccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/identities")
@RequiredArgsConstructor
public class IdentityVaultController {
    private final IdentityVaultService vaultService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Maps a real identity to an anonymous ID. 
     * Usually called during onboarding/auth.
     */
    @PostMapping("/map")
    public ResponseEntity<Map<String, UUID>> mapIdentity(@RequestBody Map<String, String> request) {
        String realIdentity = request.get("realIdentity");
        UUID anonymousId = vaultService.getOrCreateAnonymousId(realIdentity);
        return ResponseEntity.ok(Map.of("anonymousId", anonymousId));
    }

    /**
     * Registers a temporary visitor and maps their details to an Anonymous ID.
     */
    @PostMapping("/visitor")
    public ResponseEntity<Map<String, UUID>> registerVisitor(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String email = request.get("email");
        String reason = request.get("reason_for_visit");
        
        // Combine details into a single identity string for the vault
        String realIdentity = "VISITOR|" + email + "|" + name + "|" + reason;
        UUID anonymousId = vaultService.getOrCreateAnonymousId(realIdentity);
        
        return ResponseEntity.ok(Map.of("anonymousId", anonymousId));
    }

    /**
     * Restricted lookup of real identity from anonymous ID.
     * Authorized for Health Center personnel only.
     */
    @GetMapping("/lookup/{id}")
    @PreAuthorize("hasAuthority('identity:lookup')")
    public ResponseEntity<Map<String, String>> lookupIdentity(@PathVariable UUID id) {
        String requestingUser = getCurrentUser();
        String status = "SUCCESS";
        String realIdentity = null;

        try {
            realIdentity = vaultService.resolveRealIdentity(id);
            return ResponseEntity.ok(Map.of("realIdentity", realIdentity));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            status = "FAILURE_" + e.getStatusCode().toString();
            throw e;
        } catch (Exception e) {
            status = "ERROR";
            throw e;
        } finally {
            emitAuditEvent(id, requestingUser, status);
        }
    }

    private String getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private void emitAuditEvent(UUID anonymousId, String user, String status) {
        IdentityAccessEvent event = IdentityAccessEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("audit.identity.accessed")
                .timestamp(Instant.now())
                .source("circleguard-identity-service")
                .payload(IdentityAccessEvent.IdentityAccessPayload.builder()
                        .anonymousId(anonymousId)
                        .requestingUser(user)
                        .accessStatus(status)
                        .build())
                .metadata(IdentityAccessEvent.IdentityAccessMetadata.builder()
                        .correlationId(UUID.randomUUID().toString())
                        .version(1)
                        .build())
                .build();

        kafkaTemplate.send("audit.identity.accessed", event);
    }
}
