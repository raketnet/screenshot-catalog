package com.screenshots.catalog.repository;

import com.screenshots.catalog.db.DatabaseConfig;
import com.screenshots.catalog.model.Screenshot;
import com.screenshots.catalog.service.ImageDiffService;
import com.screenshots.catalog.service.ScreenshotScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExactMatchSearchTest {

    private ScreenshotRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        String jdbcUrl = "jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DatabaseConfig db = new DatabaseConfig(jdbcUrl, "sa", "");
        repository = new ScreenshotRepository(db.dataSource());
        ImageDiffService diffService = new ImageDiffService();
        ScreenshotScanService scanService = new ScreenshotScanService(repository, diffService);

        Path repoRoot = Files.createTempDirectory("repo-exact-test");
        // "Auth" and "AuthService" both contain "auth" - a substring search for "Auth" would
        // match both; an exact search for "Auth" must match only the first.
        write(repoRoot, "Release_1.0/Auth/LoginDialog/login.png", Color.RED);
        write(repoRoot, "Release_1.0/AuthService/StatusWindow/status.png", Color.BLUE);
        scanService.scanRepository(repoRoot);
    }

    private void write(Path root, String relPath, Color color) throws IOException {
        Path p = root.resolve(relPath);
        Files.createDirectories(p.getParent());
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 32, 32);
        g.dispose();
        ImageIO.write(img, "png", p.toFile());
    }

    private ScreenshotRepository.SearchFilters filters(String component) {
        return new ScreenshotRepository.SearchFilters(
                null, component, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void substringModeMatchesBothComponents() {
        List<Screenshot> results = repository.search(filters("Auth"), false);
        assertEquals(2, results.size(), "substring 'Auth' should match both Auth and AuthService");
    }

    @Test
    void exactModeMatchesOnlyTheExactComponent() {
        List<Screenshot> results = repository.search(filters("Auth"), true);
        assertEquals(1, results.size(), "exact match should only return the Auth component");
        assertEquals("Auth", results.get(0).component());
    }

    @Test
    void exactModeFindsNothingForAPartialName() {
        List<Screenshot> results = repository.search(filters("Aut"), true);
        assertTrue(results.isEmpty(), "exact match on a partial name should find nothing");
    }
}
