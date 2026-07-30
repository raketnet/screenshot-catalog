package com.screenshots.catalog.dto;

import java.util.List;

/**
 * Matches an optional sidecar file next to a screenshot: login.png ->
 * login.json in the same folder. Entirely optional - a screenshot with no
 * matching sidecar is imported normally, just without this metadata.
 *
 * Example login.json:
 * {
 *   "windowClass": "com.acme.hmi.LoginDialog",
 *   "theme": "Nimbus",
 *   "plafVersion": "1.2.0",
 *   "locale": "en_US",
 *   "screenWidth": 1920,
 *   "screenHeight": 1080,
 *   "components": [
 *     { "componentId": "usernameField", "swingClass": "JTextField" },
 *     { "componentId": "passwordField", "swingClass": "JPasswordField" },
 *     { "componentId": "loginButton",   "swingClass": "JButton" }
 *   ]
 * }
 */
public record SidecarMetadata(
        String windowClass,
        String theme,
        String plafVersion,
        String locale,
        Integer screenWidth,
        Integer screenHeight,
        List<ComponentEntry> components
) {
    public record ComponentEntry(String componentId, String swingClass) {
    }
}
