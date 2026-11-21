CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_conversations_owner ON conversations(tenant_id, user_id, created_at);
CREATE TABLE messages (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    citations JSON NOT NULL,
    route VARCHAR(40),
    cached BOOLEAN NOT NULL DEFAULT FALSE,
    tokens_in INTEGER NOT NULL DEFAULT 0,
    tokens_out INTEGER NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_messages_owner_conversation ON messages(tenant_id, user_id, conversation_id, created_at);
