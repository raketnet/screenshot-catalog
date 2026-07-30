-- Repository/Release_X/Component/Window/*.png : the folder tree IS the metadata,
-- so release/component/window are normalized entities rather than parsed strings.

CREATE TABLE releases (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,     -- e.g. "24.1" (folder "Release_24.1" with prefix stripped)
    imported_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE components (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    release_id BIGINT       NOT NULL REFERENCES releases (id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    CONSTRAINT uq_components_release_name UNIQUE (release_id, name)
);

CREATE TABLE windows (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    component_id BIGINT       NOT NULL REFERENCES components (id) ON DELETE CASCADE,
    name         VARCHAR(255) NOT NULL,
    CONSTRAINT uq_windows_component_name UNIQUE (component_id, name)
);

CREATE TABLE screenshots (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    window_id        BIGINT        NOT NULL REFERENCES windows (id) ON DELETE CASCADE,
    file_name        VARCHAR(500)  NOT NULL,
    file_path        VARCHAR(1000) NOT NULL UNIQUE,
    variant          VARCHAR(100),                 -- optional, heuristically read from file name, e.g. "dark" from login_dark.png
    width            INT           NOT NULL,
    height           INT           NOT NULL,
    file_size_bytes  BIGINT        NOT NULL,
    sha256           CHAR(64)      NOT NULL,        -- exact-duplicate detection
    phash            BIGINT        NOT NULL,        -- perceptual hash, near-duplicate / similarity search
    captured_at      TIMESTAMP     NOT NULL,         -- file's last-modified time at scan time
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_components_release ON components (release_id);
CREATE INDEX idx_windows_component ON windows (component_id);
CREATE INDEX idx_screenshots_window ON screenshots (window_id);
CREATE INDEX idx_screenshots_sha256 ON screenshots (sha256);
CREATE INDEX idx_screenshots_phash ON screenshots (phash);
CREATE INDEX idx_screenshots_file_name ON screenshots (file_name);
