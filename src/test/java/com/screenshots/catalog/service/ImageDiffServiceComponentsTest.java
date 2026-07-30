package com.screenshots.catalog.service;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class ImageDiffServiceComponentsTest {

    private BufferedImage solidColor(int width, int height, Color color) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return img;
    }

    private BufferedImage withRedSquare(int width, int height, Color base) {
        BufferedImage img = solidColor(width, height, base);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(width / 4, height / 4, width / 4, height / 4);
        g.dispose();
        return img;
    }

    @Test
    void identicalImagesHaveZeroHashDistanceAndSsimOfOne() {
        BufferedImage a = solidColor(64, 64, Color.BLUE);
        BufferedImage b = solidColor(64, 64, Color.BLUE);

        long hashA = PerceptualHash.compute(a);
        long hashB = PerceptualHash.compute(b);
        assertEquals(0, PerceptualHash.hammingDistance(hashA, hashB));
        assertEquals(100.0, PerceptualHash.similarityPercent(0));

        double ssim = SsimCalculator.compute(a, b);
        assertEquals(1.0, ssim, 0.0001);
    }

    @Test
    void differentImagesHaveNonZeroHashDistanceAndLowerSsim() {
        BufferedImage a = solidColor(64, 64, Color.WHITE);
        BufferedImage b = withRedSquare(64, 64, Color.WHITE);

        long hashA = PerceptualHash.compute(a);
        long hashB = PerceptualHash.compute(b);
        assertTrue(PerceptualHash.hammingDistance(hashA, hashB) > 0);

        double ssim = SsimCalculator.compute(a, b);
        assertTrue(ssim < 1.0);
    }

    @Test
    void pHashToleratesDifferentResolutions() {
        BufferedImage small = solidColor(32, 32, Color.GREEN);
        BufferedImage large = solidColor(256, 256, Color.GREEN);

        long hashSmall = PerceptualHash.compute(small);
        long hashLarge = PerceptualHash.compute(large);

        // Same solid color at different resolutions should hash near-identically.
        assertTrue(PerceptualHash.hammingDistance(hashSmall, hashLarge) <= 2);
    }

    @Test
    void fullDiffReportsPixelPercentageOnlyWhenDimensionsMatch() throws Exception {
        ImageDiffService service = new ImageDiffService();

        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("diff-test");
        java.nio.file.Path left = dir.resolve("left.png");
        java.nio.file.Path right = dir.resolve("right-diff-size.png");

        javax.imageio.ImageIO.write(solidColor(64, 64, Color.WHITE), "png", left.toFile());
        javax.imageio.ImageIO.write(solidColor(32, 32, Color.WHITE), "png", right.toFile());

        var result = service.diff(left, right);
        assertFalse(result.dimensionsMatch());
        assertNull(result.pixelDifferencePercentage());
        assertNull(result.diffImage());
        assertNotNull(result.note());
    }
}
