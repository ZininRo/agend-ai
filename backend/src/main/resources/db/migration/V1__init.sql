CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE users (
    id           uuid PRIMARY KEY,
    display_name varchar(100),
    timezone     varchar(64) NOT NULL DEFAULT 'Europe/Moscow',
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL
);

-- MVP: единственный пользователь "default"
INSERT INTO users (id, display_name, timezone, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'default', 'Europe/Moscow', now(), now());

CREATE TABLE items (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id),
    type        varchar(20) NOT NULL CHECK (type IN ('TASK', 'MEETING', 'REMINDER')),
    title       varchar(255) NOT NULL,
    description text,
    status      varchar(20) NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO', 'DONE', 'CANCELLED')),
    start_at    timestamptz,
    end_at      timestamptz,
    due_at      timestamptz,
    place       varchar(255),
    deleted_at  timestamptz,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT chk_items_end_after_start CHECK (end_at IS NULL OR start_at IS NULL OR end_at > start_at)
);

CREATE INDEX idx_items_user_start ON items (user_id, start_at);
CREATE INDEX idx_items_user_due ON items (user_id, due_at);
CREATE INDEX idx_items_fts ON items
    USING GIN (to_tsvector('russian', title || ' ' || coalesce(description, '')));
CREATE INDEX idx_items_title_trgm ON items USING GIN (title gin_trgm_ops);

CREATE TABLE sessions (
    id                    uuid PRIMARY KEY,
    user_id               uuid NOT NULL REFERENCES users (id),
    last_item_id          uuid REFERENCES items (id) ON DELETE SET NULL,
    pending_clarification jsonb,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL
);

CREATE TABLE messages (
    id                uuid PRIMARY KEY,
    user_id           uuid NOT NULL REFERENCES users (id),
    session_id        uuid NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    role              varchar(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    content           text NOT NULL,
    input_type        varchar(20),
    intent            varchar(50),
    item_id           uuid REFERENCES items (id) ON DELETE SET NULL,
    client_message_id uuid,
    intent_payload    jsonb,
    response_json     jsonb,
    timezone          varchar(64) NOT NULL DEFAULT 'Europe/Moscow',
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL
);

CREATE INDEX idx_messages_session_created ON messages (session_id, created_at);
CREATE UNIQUE INDEX uk_messages_session_client_msg
    ON messages (session_id, client_message_id) WHERE client_message_id IS NOT NULL;
