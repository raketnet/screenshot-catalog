-- V1 only indexes components(release_id) and windows(component_id) - fine for
-- "give me everything under this release/component," but a name-only lookup
-- (e.g. WHERE c.name = ?, independent of which release) can't use the
-- composite (release_id, name) / (component_id, name) unique indexes, since
-- name isn't the leading column in either. Cheap to add, safe on any size
-- catalog, and closes a real gap in the exact-match search path
-- (ScreenshotRepository.search(..., exact=true)).

CREATE INDEX idx_components_name ON components (name);
CREATE INDEX idx_windows_name ON windows (name);
