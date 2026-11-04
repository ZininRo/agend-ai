ALTER TABLE items
    ADD COLUMN source varchar(32) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN all_day boolean NOT NULL DEFAULT false;

ALTER TABLE items
    ADD CONSTRAINT chk_items_source CHECK (source IN ('LOCAL', 'GOOGLE_CALENDAR'));

CREATE TABLE calendar_connections (
    id                      uuid PRIMARY KEY,
    user_id                 uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider                varchar(32) NOT NULL,
    access_token_enc        text,
    refresh_token_enc       text,
    access_token_expires_at timestamptz,
    granted_scopes          text,
    status                  varchar(32) NOT NULL DEFAULT 'CONNECTED',
    sync_enabled            boolean NOT NULL DEFAULT true,
    last_sync_at            timestamptz,
    last_error              varchar(255),
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    CONSTRAINT uk_calendar_connection_user_provider UNIQUE(user_id, provider),
    CONSTRAINT chk_calendar_connection_provider CHECK (provider IN ('GOOGLE')),
    CONSTRAINT chk_calendar_connection_status CHECK (status IN ('CONNECTED', 'REAUTH_REQUIRED', 'ERROR', 'DISCONNECTED'))
);
CREATE INDEX idx_calendar_connections_user ON calendar_connections(user_id);

CREATE TABLE external_calendars (
    id                        uuid PRIMARY KEY,
    connection_id             uuid NOT NULL REFERENCES calendar_connections(id) ON DELETE CASCADE,
    provider_calendar_id_enc  text NOT NULL,
    provider_calendar_id_hash varchar(64) NOT NULL,
    summary_enc               text,
    timezone                  varchar(64),
    access_role               varchar(32),
    primary_calendar          boolean NOT NULL DEFAULT false,
    available                 boolean NOT NULL DEFAULT true,
    sync_enabled              boolean NOT NULL DEFAULT false,
    write_target              boolean NOT NULL DEFAULT false,
    sync_token_enc            text,
    last_sync_at              timestamptz,
    last_full_sync_at         timestamptz,
    sync_requested_at         timestamptz,
    watch_channel_id          varchar(128),
    watch_resource_id         varchar(255),
    watch_token_hash          varchar(64),
    watch_expires_at          timestamptz,
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,
    CONSTRAINT uk_external_calendar_provider_id UNIQUE(connection_id, provider_calendar_id_hash)
);
CREATE INDEX idx_external_calendars_connection ON external_calendars(connection_id);
CREATE INDEX idx_external_calendars_sync ON external_calendars(sync_enabled, available, sync_requested_at);
CREATE UNIQUE INDEX uk_external_calendar_write_target
    ON external_calendars(connection_id) WHERE write_target = true;
CREATE UNIQUE INDEX uk_external_calendar_watch_channel
    ON external_calendars(watch_channel_id) WHERE watch_channel_id IS NOT NULL;

CREATE TABLE item_calendar_links (
    id                     uuid PRIMARY KEY,
    item_id                uuid NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    external_calendar_id   uuid NOT NULL REFERENCES external_calendars(id) ON DELETE CASCADE,
    external_event_id      varchar(1024) NOT NULL,
    external_event_id_hash varchar(64) NOT NULL,
    external_etag          varchar(255),
    external_updated_at    timestamptz,
    last_synced_at         timestamptz,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    CONSTRAINT uk_item_calendar_link_item UNIQUE(item_id),
    CONSTRAINT uk_item_calendar_link_event UNIQUE(external_calendar_id, external_event_id_hash)
);
CREATE INDEX idx_item_calendar_links_calendar ON item_calendar_links(external_calendar_id);

CREATE TABLE calendar_sync_outbox (
    item_id      uuid PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    operation    varchar(16) NOT NULL,
    attempts     integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    last_error   varchar(255),
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT chk_calendar_outbox_operation CHECK (operation IN ('UPSERT', 'DELETE'))
);
CREATE INDEX idx_calendar_sync_outbox_ready ON calendar_sync_outbox(available_at, updated_at);

CREATE TABLE calendar_oauth_states (
    state_hash varchar(64) PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_calendar_oauth_states_expires ON calendar_oauth_states(expires_at);
