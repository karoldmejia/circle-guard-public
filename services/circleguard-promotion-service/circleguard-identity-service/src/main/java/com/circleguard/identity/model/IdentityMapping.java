package com.circleguard.identity.model;

import com.circleguard.identity.util.IdentityEncryptionConverter;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "identity_mappings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdentityMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID anonymousId;

    @Convert(converter = IdentityEncryptionConverter.class)
    @Column(name = "real_identity_encrypted", nullable = false)
    private String realIdentity;

    @Column(name = "identity_hash", unique = true, nullable = false)
    private String identityHash;

    @Column(nullable = false)
    private String salt;
}
