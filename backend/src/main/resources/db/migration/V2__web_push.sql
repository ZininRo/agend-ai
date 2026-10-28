-- Browser endpoints are sensitive credentials: never log them.
CREATE TABLE push_subscriptions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    endpoint text NOT NULL UNIQUE,
    p256dh text NOT NULL,
    auth_secret text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_push_subscriptions_user ON push_subscriptions(user_id);

-- One notification per subscription, item and exact scheduled instant.
-- A changed meeting time generates a different key; obsolete entries are skipped.
CREATE TABLE push_deliveries (
    id uuid PRIMARY KEY,
    subscription_id uuid NOT NULL REFERENCES push_subscriptions(id) ON DELETE CASCADE,
    item_id uuid NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    scheduled_at timestamptz NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(subscription_id, item_id, scheduled_at)
);
CREATE INDEX idx_push_deliveries_pending ON push_deliveries(next_attempt_at) WHERE status = 'PENDING';
