package com.screenshots.catalog.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Every security-relevant setting here defaults to "just works for local
 * testing" - auth off, generous rate limit, scan restricted to your home
 * directory and the app's working directory (rather than the whole
 * filesystem). Stricter behavior only kicks in if you explicitly configure
 * it, which you'd do when running this somewhere other than your own machine.
 *
 * Every setting can be given three ways, in this precedence order (highest
 * wins): an environment variable, a value in a properties file, or the
 * built-in default. The properties file is looked for at
 * {@code ./config.properties} (relative to the working directory the jar
 * was launched from) unless SCREENSHOT_CONFIG_FILE points somewhere else.
 * It's entirely optional - if the file doesn't exist, everything just falls
 * back to env vars/defaults exactly as before. Keys in the file use the same
 * names as the environment variables (e.g. {@code SCREENSHOT_PORT=8080}),
 * so there's one name to remember regardless of which way you set it. See
 * config.properties in the project root for a template.
 *
 * Env vars are checked first (not the file) so that a container/CI
 * environment can always override a properties file without editing it -
 * the file is meant for "the settings I want checked into version control
 * or set once for this machine," env vars for "override just this one
 * thing for this one run."
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
        return resolveConfig(System.getenv(), loadPropertiesFile());
    }

    /**
     * The actual precedence logic (env > properties file > default), as a pure function of its
     * inputs - separated from fromEnv()'s "go discover the real environment and file" job
     * specifically so it's testable without needing to set real environment variables (which a
     * running JVM can't portably do to itself) or touch the real filesystem.
     */
    static AppConfig resolveConfig(Map<String, String> env, Properties props) {
        String jdbcUrl = resolve(env, props, "SCREENSHOT_DB_URL", "jdbc:h2:file:./data/screenshot-catalog;AUTO_SERVER=TRUE");
        String dbUser = resolve(env, props, "SCREENSHOT_DB_USER", "sa");
        String dbPassword = resolve(env, props, "SCREENSHOT_DB_PASSWORD", "");
        int port = Integer.parseInt(resolve(env, props, "SCREENSHOT_PORT", "7070"));

        List<Path> allowedScanRoots = parseAllowedRoots(resolveRaw(env, props, "SCREENSHOT_ALLOWED_ROOTS"));

        Optional<String> apiKey = Optional.ofNullable(resolveRaw(env, props, "SCREENSHOT_API_KEY"))
                .filter(s -> !s.isBlank());

        List<String> extraAllowedOrigins = splitCsv(resolveRaw(env, props, "SCREENSHOT_ALLOWED_ORIGINS"));

        int rateLimitPerMinute = Integer.parseInt(resolve(env, props, "SCREENSHOT_RATE_LIMIT_PER_MINUTE", "300"));

        return new AppConfig(jdbcUrl, dbUser, dbPassword, port, allowedScanRoots, apiKey,
                extraAllowedOrigins, rateLimitPerMinute);
    }

    /**
     * Env var wins if set and non-blank; otherwise the properties file's value if present and
     * non-blank; otherwise the built-in default. Used for settings that always have a concrete
     * value (there's no meaningful "unset" state to preserve past this point).
     */
    private static String resolve(Map<String, String> env, Properties props, String key, String defaultValue) {
        String raw = resolveRaw(env, props, key);
        return (raw == null || raw.isBlank()) ? defaultValue : raw;
    }

    /**
     * Same precedence as resolve(), but returns null (not a default) when neither source has a
     * value - for settings where "unset" is itself meaningful (e.g. no API key configured at all,
     * meaning auth is off, versus an explicit empty string).
     */
    private static String resolveRaw(Map<String, String> env, Properties props, String key) {
        String envVal = env.get(key);
        if (envVal != null && !envVal.isBlank()) return envVal;
        String propVal = props.getProperty(key);
        if (propVal != null && !propVal.isBlank()) return propVal;
        return null;
    }

    private static Properties loadPropertiesFile() {
        Properties props = new Properties();
        String configFilePath = System.getenv().getOrDefault("SCREENSHOT_CONFIG_FILE", "./config.properties");
        Path path = Path.of(configFilePath);

        if (!Files.exists(path)) {
            return props; // no file - not an error, everything just falls back to env vars/defaults
        }
        if (!Files.isRegularFile(path)) {
            System.err.println("SCREENSHOT_CONFIG_FILE points at something that isn't a regular file, ignoring: " + path);
            return props;
        }

        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            System.out.println("Loaded configuration from " + path.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Could not read config file " + path.toAbsolutePath() + ": " + e.getMessage()
                    + " - falling back to environment variables/defaults for anything it would have set.");
        }
        return props;
    }

    /**
     * Default (nothing set via env var or properties file): your home directory + the app's
     * current working directory. Covers where a Repository/ folder realistically lives for local
     * testing, without allowing a scan request to point anywhere on disk (e.g. "/etc" or "/").
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
