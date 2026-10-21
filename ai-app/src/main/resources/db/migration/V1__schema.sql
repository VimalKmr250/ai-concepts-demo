-- ---------------------------------------------------------------------------
-- Spring AI JDBC chat memory (same DDL as spring-ai's schema-postgresql.sql,
-- owned by Flyway so the schema is versioned with the app)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS spring_ai_chat_memory (
    conversation_id VARCHAR(36) NOT NULL,
    content         TEXT        NOT NULL,
    type            VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp"     TIMESTAMP   NOT NULL,
    sequence_id     BIGINT      NOT NULL
);
CREATE INDEX IF NOT EXISTS spring_ai_chat_memory_conversation_id_timestamp_idx
    ON spring_ai_chat_memory (conversation_id, "timestamp");
CREATE INDEX IF NOT EXISTS spring_ai_chat_memory_conversation_id_sequence_id_idx
    ON spring_ai_chat_memory (conversation_id, sequence_id);

-- ---------------------------------------------------------------------------
-- Knowledge-base document registry (demo 3: RAG). The chunks + embeddings live in
-- Spring AI's vector_store table; this table tracks what was ingested (and dedupes by hash).
-- ---------------------------------------------------------------------------
CREATE TABLE kb_document (
    id          BIGSERIAL PRIMARY KEY,
    source      VARCHAR(255) NOT NULL UNIQUE,
    category    VARCHAR(20)  NOT NULL,
    sha256      CHAR(64)     NOT NULL,
    chunks      INT          NOT NULL,
    ingested_at TIMESTAMP    NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- IT helpdesk domain (demo 4: tools + human-in-the-loop)
-- ---------------------------------------------------------------------------
CREATE TABLE employee (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(40)  NOT NULL UNIQUE,
    full_name   VARCHAR(120) NOT NULL,
    email       VARCHAR(160) NOT NULL,
    department  VARCHAR(60)  NOT NULL,
    location    VARCHAR(60)  NOT NULL,
    manager     VARCHAR(40)
);

CREATE TABLE asset (
    id              BIGSERIAL PRIMARY KEY,
    asset_tag       VARCHAR(20)  NOT NULL UNIQUE,
    type            VARCHAR(30)  NOT NULL,
    model           VARCHAR(80)  NOT NULL,
    assigned_to     VARCHAR(40)  REFERENCES employee (username),
    purchased_on    DATE         NOT NULL,
    warranty_until  DATE         NOT NULL,
    status          VARCHAR(20)  NOT NULL
);

CREATE SEQUENCE ticket_number_seq START 1007;

CREATE TABLE ticket (
    id          BIGSERIAL PRIMARY KEY,
    ticket_no   VARCHAR(20)  NOT NULL UNIQUE,
    username    VARCHAR(40)  NOT NULL REFERENCES employee (username),
    asset_tag   VARCHAR(20)  REFERENCES asset (asset_tag),
    category    VARCHAR(30)  NOT NULL,
    priority    VARCHAR(10)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    summary     VARCHAR(500) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor       VARCHAR(60)  NOT NULL,
    action      VARCHAR(60)  NOT NULL,
    details     VARCHAR(1000),
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);
