CREATE TABLE chunk_guides (
    item_id BIGINT PRIMARY KEY REFERENCES work_items(id),
    version BIGINT NOT NULL DEFAULT 0,
    purpose VARCHAR(8000) NOT NULL,
    learning_goal VARCHAR(8000) NOT NULL,
    steps VARCHAR(8000) NOT NULL,
    reflection VARCHAR(8000) NOT NULL
);
