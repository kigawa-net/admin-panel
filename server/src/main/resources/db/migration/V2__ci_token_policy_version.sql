-- Optimistic concurrency substrate for #231. Existing records start at version 1.
ALTER TABLE ci_token_policy
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
