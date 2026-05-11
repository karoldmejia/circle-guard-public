package com.circleguard.identity.event;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IdentityAccessEvent {
    private String eventId;
    private String eventType;
    private Instant timestamp;
    private String source;
    private IdentityAccessPayload payload;
    private IdentityAccessMetadata metadata;

    @Data
    @Builder
    public static class IdentityAccessPayload {
        private UUID anonymousId;
        private String requestingUser;
        private String accessStatus;
    }

    @Data
    @Builder
    public static class IdentityAccessMetadata {
        private String correlationId;
        private int version;
    }
}
