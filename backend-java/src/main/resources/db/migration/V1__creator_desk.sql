CREATE TABLE desk_tasks (
    owner VARCHAR(100) NOT NULL,
    task_id VARCHAR(100) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    record_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner, task_id)
);

CREATE INDEX desk_tasks_owner_updated_idx
    ON desk_tasks (owner, updated_at DESC, task_id);

CREATE TABLE desk_events (
    seq BIGSERIAL PRIMARY KEY,
    owner VARCHAR(100) NOT NULL,
    task_id VARCHAR(100) NOT NULL,
    kind VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT desk_events_task_fk FOREIGN KEY (owner, task_id)
        REFERENCES desk_tasks (owner, task_id) ON DELETE CASCADE
);

CREATE INDEX desk_events_task_seq_idx
    ON desk_events (owner, task_id, seq);
