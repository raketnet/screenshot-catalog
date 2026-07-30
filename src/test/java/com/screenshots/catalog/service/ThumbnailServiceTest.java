package com.screenshots.catalog.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ThumbnailServiceTest {

    private Path writeSolidPng(Path dir, String name, int width, int height, Color color) throws Exception {
        Path path = dir.resolve(name);
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ImageIO.write(img, "png", path.toFile());
        return path;
    }

    private String fakeHash(String seed) {
        return "hash-" + seed;
    }

    @Test
    void downscalesLargeImagesToTheRequestedMaxDimension() throws Exception {
        Path dir = Files.createTempDirectory("thumb-test");
        Path large = writeSolidPng(dir, "large.png", 1920, 1080, Color.BLUE);

        ThumbnailService service = new ThumbnailService();
        byte[] thumbBytes = service.getThumbnail(large, fakeHash("large"), 480);

        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(thumbBytes));
        assertNotNull(thumb);
        assertEquals(480, Math.max(thumb.getWidth(), thumb.getHeight()));
        // aspect ratio preserved (1920x1080 -> 16:9)
        assertEquals(270, thumb.getHeight());
    }

    @Test
    void doesNotUpscaleImagesAlreadySmallerThanTheMaxDimension() throws Exception {
        Path dir = Files.createTempDirectory("thumb-test");
        Path small = writeSolidPng(dir, "small.png", 100, 60, Color.RED);

        ThumbnailService service = new ThumbnailService();
        byte[] thumbBytes = service.getThumbnail(small, fakeHash("small"), 480);

        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(thumbBytes));
        assertEquals(100, thumb.getWidth());
        assertEquals(60, thumb.getHeight());
    }

    @Test
    void identicalContentHashesShareOneCacheEntryEvenFromDifferentFiles() throws Exception {
        Path dir = Files.createTempDirectory("thumb-test");
        Path a = writeSolidPng(dir, "a.png", 800, 600, Color.GREEN);
        Path b = writeSolidPng(dir, "b.png", 800, 600, Color.GREEN); // same content, different file/path

        ThumbnailService service = new ThumbnailService();
        String sameHash = fakeHash("shared-content");

        service.getThumbnail(a, sameHash, 200);
        assertEquals(1, service.cacheSize());

        service.getThumbnail(b, sameHash, 200); // same hash+size -> cache hit, no new entry
        assertEquals(1, service.cacheSize());
    }

    @Test
    void differentContentHashesGetSeparateCacheEntries() throws Exception {
        Path dir = Files.createTempDirectory("thumb-test");
        Path a = writeSolidPng(dir, "a.png", 800, 600, Color.GREEN);
        Path b = writeSolidPng(dir, "b.png", 800, 600, Color.MAGENTA);

        ThumbnailService service = new ThumbnailService();
        service.getThumbnail(a, fakeHash("content-a"), 200);
        service.getThumbnail(b, fakeHash("content-b"), 200);

        assertEquals(2, service.cacheSize());
    }

    @Test
    void clampDimensionEnforcesSaneBounds() {
        assertEquals(480, ThumbnailService.clampDimension(null)); // default
        assertEquals(32, ThumbnailService.clampDimension(1));     // floor
        assertEquals(800, ThumbnailService.clampDimension(5000)); // ceiling
        assertEquals(600, ThumbnailService.clampDimension(600));  // pass-through within bounds
    }
}
