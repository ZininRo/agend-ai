CREATE TABLE user_identities (
    id               uuid PRIMARY KEY,
    user_id          uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider         varchar(32) NOT NULL,
    provider_subject varchar(255) NOT NULL,
    email_enc        text,
    email_hash       varchar(64),
    password_hash    varchar(100),
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT uk_user_identity_provider_subject UNIQUE(provider, provider_subject)
);

CREATE UNIQUE INDEX uk_user_identities_email_hash
    ON user_identities(email_hash) WHERE email_hash IS NOT NULL;
CREATE INDEX idx_user_identities_user ON user_identities(user_id);

CREATE TABLE auth_sessions (
    id                 uuid PRIMARY KEY,
    user_id            uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id          uuid NOT NULL,
    refresh_token_hash varchar(64) NOT NULL UNIQUE,
    expires_at         timestamptz NOT NULL,
    revoked_at         timestamptz,
    last_used_at       timestamptz,
    replaced_by_id     uuid REFERENCES auth_sessions(id) ON DELETE SET NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL
);

CREATE INDEX idx_auth_sessions_user ON auth_sessions(user_id);
CREATE INDEX idx_auth_sessions_family ON auth_sessions(family_id);
CREATE INDEX idx_auth_sessions_expires ON auth_sessions(expires_at);
