-- Optional per-screenshot Swing metadata, populated only when the capture
-- tool exports a sidecar JSON file next to the screenshot (see
-- ScreenshotScanService / README for the sidecar format). A screenshot with
-- no sidecar simply has no rows here - nothing about ingestion requires it.

CREATE TABLE screenshot_metadata (
    screenshot_id  BIGINT PRIMARY KEY REFERENCES screenshots (id) ON DELETE CASCADE,
    window_class   VARCHAR(500),   -- e.g. com.acme.hmi.LoginDialog
    theme          VARCHAR(100),   -- e.g. Nimbus, Metal, FlatLaf-Dark
    plaf_version   VARCHAR(100),
    locale         VARCHAR(20),    -- e.g. en_US
    screen_width   INT,
    screen_height  INT
);

CREATE TABLE screenshot_components (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    screenshot_id  BIGINT       NOT NULL REFERENCES screenshots (id) ON DELETE CASCADE,
    component_id   VARCHAR(255),          -- the app's own component identifier, if it has one
    swing_class    VARCHAR(255) NOT NULL  -- e.g. JTable, JButton, JTextField
);

CREATE INDEX idx_screenshot_components_screenshot ON screenshot_components (screenshot_id);
CREATE INDEX idx_screenshot_components_swing_class ON screenshot_components (swing_class);
CREATE INDEX idx_screenshot_metadata_theme ON screenshot_metadata (theme);
CREATE INDEX idx_screenshot_metadata_plaf ON screenshot_metadata (plaf_version);
