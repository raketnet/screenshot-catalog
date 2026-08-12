package com.screenshots.catalog.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @Test
    void defaultsApplyWhenNothingIsSetAnywhere() {
        AppConfig config = AppConfig.resolveConfig(Map.of(), new Properties());

        assertEquals(7070, config.port());
        assertEquals("sa", config.dbUser());
        assertEquals("", config.dbPassword());
        assertTrue(config.jdbcUrl().contains("screenshot-catalog"));
        assertTrue(config.apiKey().isEmpty());
        assertEquals(300, config.rateLimitPerMinute());
        assertTrue(config.extraAllowedOrigins().isEmpty());
    }

    @Test
    void propertiesFileValueIsUsedWhenNoEnvVarIsSet() {
        Properties props = new Properties();
        props.setProperty("SCREENSHOT_PORT", "8080");

        AppConfig config = AppConfig.resolveConfig(Map.of(), props);

        assertEquals(8080, config.port());
    }

    @Test
    void envVarOverridesThePropertiesFileValue() {
        Properties props = new Properties();
        props.setProperty("SCREENSHOT_PORT", "8080");
        Map<String, String> env = Map.of("SCREENSHOT_PORT", "9090");

        AppConfig config = AppConfig.resolveConfig(env, props);

        assertEquals(9090, config.port(), "An explicitly set env var must win over the properties file");
    }

    @Test
    void blankEnvVarDoesNotCountAsSetAndFallsThroughToThePropertiesFile() {
        Properties props = new Properties();
        props.setProperty("SCREENSHOT_PORT", "8080");
        Map<String, String> env = new HashMap<>();
        env.put("SCREENSHOT_PORT", "   "); // present but blank - should be treated as unset

        AppConfig config = AppConfig.resolveConfig(env, props);

        assertEquals(8080, config.port());
    }

    @Test
    void apiKeyIsEmptyWhenUnsetAnywhere() {
        AppConfig config = AppConfig.resolveConfig(Map.of(), new Properties());
        assertTrue(config.apiKey().isEmpty());
    }

    @Test
    void apiKeyFromPropertiesFileIsUsedWhenNoEnvVarIsSet() {
        Properties props = new Properties();
        props.setProperty("SCREENSHOT_API_KEY", "secret-from-file");

        AppConfig config = AppConfig.resolveConfig(Map.of(), props);

        assertEquals("secret-from-file", config.apiKey().orElseThrow());
    }

    @Test
    void apiKeyFromEnvVarOverridesThePropertiesFileValue() {
        Properties props = new Properties();
        props.setProperty("SCREENSHOT_API_KEY", "secret-from-file");
        Map<String, String> env = Map.of("SCREENSHOT_API_KEY", "secret-from-env");

        AppConfig config = AppConfig.resolveConfig(env, props);

        assertEquals("secret-from-env", config.apiKey().orElseThrow());
    }



    @Test
    void extraAllowedOriginsCanBeSetFromThePropertiesFile() {
        Properties props = new Properties();
        props.setProperty("SCREENSHOT_ALLOWED_ORIGINS", "https://a.example.com, https://b.example.com");

        AppConfig config = AppConfig.resolveConfig(Map.of(), props);

        assertEquals(List.of("https://a.example.com", "https://b.example.com"), config.extraAllowedOrigins());
    }

    @Test
    void multiplePropertiesInTheSameFileAreAllRespectedTogether() {
        Properties props = new Properties();
        props.setProperty("SCREENSHOT_PORT", "9000");
        props.setProperty("SCREENSHOT_DB_USER", "custom_user");
        props.setProperty("SCREENSHOT_RATE_LIMIT_PER_MINUTE", "50");

        AppConfig config = AppConfig.resolveConfig(Map.of(), props);

        assertEquals(9000, config.port());
        assertEquals("custom_user", config.dbUser());
        assertEquals(50, config.rateLimitPerMinute());
    }
}
