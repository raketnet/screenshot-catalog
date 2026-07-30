package com.screenshots.catalog.config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Every security-relevant setting here defaults to "just works for local
 * testing" - auth off, generous rate limit, scan restricted to your home
 * directory and the app's working directory (rather than the whole
 * filesystem). Stricter behavior only kicks in if you explicitly configure
 * it, which you'd do when running this somewhere other than your own machine.
 *
 * TLS is deliberately not configured here - this app speaks plain HTTP.
 * Terminate TLS with a reverse proxy (Caddy, nginx, etc.) in front of it if
 * you're running this somewhere reachable over an untrusted network; that's
 * a more robust and better-tested setup than embedding Jetty SSL config in
 * the app itself.
 */
public record AppConfig(
        String jdbcUrl,
        String dbUser,
        String dbPassword,
        int port,
        List<Path> allowedScanRoots,
        Optional<String> apiKey,
        List<String> extraAllowedOrigins,
        int rateLimitPerMinute
) {

    public static AppConfig fromEnv() {
        String jdbcUrl = System.getenv().getOrDefault(
                "SCREENSHOT_DB_URL",
                "jdbc:h2:file:./data/screenshot-catalog;AUTO_SERVER=TRUE"
        );
        String dbUser = System.getenv().getOrDefault("SCREENSHOT_DB_USER", "sa");
        String dbPassword = System.getenv().getOrDefault("SCREENSHOT_DB_PASSWORD", "");
        int port = Integer.parseInt(System.getenv().getOrDefault("SCREENSHOT_PORT", "7070"));

        List<Path> allowedScanRoots = parseAllowedRoots(System.getenv("SCREENSHOT_ALLOWED_ROOTS"));

        Optional<String> apiKey = Optional.ofNullable(System.getenv("SCREENSHOT_API_KEY"))
                .filter(s -> !s.isBlank());

        List<String> extraAllowedOrigins = splitCsv(System.getenv("SCREENSHOT_ALLOWED_ORIGINS"));

        int rateLimitPerMinute = Integer.parseInt(
                System.getenv().getOrDefault("SCREENSHOT_RATE_LIMIT_PER_MINUTE", "300"));

        return new AppConfig(jdbcUrl, dbUser, dbPassword, port, allowedScanRoots, apiKey,
                extraAllowedOrigins, rateLimitPerMinute);
    }

    /**
     * Default (no env var set): your home directory + the app's current working
     * directory. Covers where a Repository/ folder realistically lives for local
     * testing, without allowing a scan request to point anywhere on disk (e.g.
     * "/etc" or "/"). Set SCREENSHOT_ALLOWED_ROOTS explicitly to override.
     */
    private static List<Path> parseAllowedRoots(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of(
                    Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(),
                    Path.of("").toAbsolutePath().normalize()
            );
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> Path.of(s).toAbsolutePath().normalize())
                .toList();
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
