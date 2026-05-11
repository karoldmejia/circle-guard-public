-- Identity Vault Schema
-- Stores the mapping between real and anonymous identities

CREATE TABLE identity_mappings (
    anonymous_id UUID PRIMARY KEY,
    real_identity_encrypted BYTEA NOT NULL, -- Encrypted real identity (e.g. email/ID)
    identity_hash VARCHAR(64) NOT NULL,    -- SHA-256 hash (blind index)
    salt VARCHAR(255) NOT NULL,            -- Per-user salt for SHA-256 hashing
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_identity_anonymous ON identity_mappings(anonymous_id);
CREATE UNIQUE INDEX idx_identity_hash ON identity_mappings(identity_hash);
