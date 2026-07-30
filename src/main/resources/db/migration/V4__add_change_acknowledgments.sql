-- Acknowledgment/review status for a "change" - a pair of screenshot ids.
-- Deliberately generic rather than tied to a specific release comparison:
-- the same (left_id, right_id) pair might be looked at via /api/diff (e.g.
-- from a timeline connector) or via /api/compare/releases (the release
-- report) - both should see and be able to set the same status. Exactly one
-- of left_id/right_id is null for a NEW or REMOVED screenshot (nothing to
-- pair it with on the other side - only left_id or right_id is set, not both).
-- Both are set for CHANGED/UNCHANGED.
--
-- No DB-level uniqueness constraint on (left_id, right_id): H2, like most
-- SQL engines, does not treat two NULLs as equal for uniqueness purposes,
-- which would let duplicate rows in for NEW/REMOVED pairs. Get-or-create is
-- handled in application code instead (see ScreenshotRepository), same
-- pattern already used for releases/components/windows.

CREATE TABLE change_acknowledgments (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    left_id          BIGINT REFERENCES screenshots (id) ON DELETE CASCADE,
    right_id         BIGINT REFERENCES screenshots (id) ON DELETE CASCADE,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    note             VARCHAR(1000),
    acknowledged_by  VARCHAR(255),
    acknowledged_at  TIMESTAMP,
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_change_ack_left ON change_acknowledgments (left_id);
CREATE INDEX idx_change_ack_right ON change_acknowledgments (right_id);
CREATE INDEX idx_change_ack_pair ON change_acknowledgments (left_id, right_id);
