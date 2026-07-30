package com.screenshots.catalog.service;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;

/**
 * Structural Similarity Index (SSIM), computed as the average SSIM over
 * non-overlapping 8x8 blocks rather than a full sliding Gaussian window -
 * a standard simplification that's much cheaper and close enough for
 * screenshot-to-screenshot regression comparison.
 *
 * Score is in [-1, 1]; 1.0 means structurally identical. In practice for
 * screenshots this stays in [0, 1], with values above ~0.95 indicating
 * "looks the same" and anything meaningfully lower flagging a real
 * structural/layout change.
 */
public final class SsimCalculator {

    private static final double K1 = 0.01;
    private static final double K2 = 0.03;
    private static final double L = 255; // dynamic range of 8-bit grayscale
    private static final double C1 = (K1 * L) * (K1 * L);
    private static final double C2 = (K2 * L) * (K2 * L);
    private static final int BLOCK = 8;

    private SsimCalculator() {
    }

    /**
     * Computes SSIM between two images. If dimensions differ, {@code right} is
     * resized to match {@code left} first - this makes the score approximate
     * but still meaningful as a "how similar do these look" signal.
     */
    public static double compute(BufferedImage left, BufferedImage right) {
        BufferedImage b = (left.getWidth() == right.getWidth() && left.getHeight() == right.getHeight())
                ? right
                : resize(right, left.getWidth(), left.getHeight());

        double[][] a = toGrayscale(left);
        double[][] gb = toGrayscale(b);

        int width = left.getWidth();
        int height = left.getHeight();

        double total = 0;
        int blocks = 0;

        for (int by = 0; by + BLOCK <= height; by += BLOCK) {
            for (int bx = 0; bx + BLOCK <= width; bx += BLOCK) {
                total += blockSsim(a, gb, bx, by);
                blocks++;
            }
        }

        return blocks == 0 ? 1.0 : total / blocks;
    }

    private static double blockSsim(double[][] a, double[][] b, int bx, int by) {
        int n = BLOCK * BLOCK;
        double meanA = 0, meanB = 0;
        for (int y = by; y < by + BLOCK; y++) {
            for (int x = bx; x < bx + BLOCK; x++) {
                meanA += a[y][x];
                meanB += b[y][x];
            }
        }
        meanA /= n;
        meanB /= n;

        double varA = 0, varB = 0, covAB = 0;
        for (int y = by; y < by + BLOCK; y++) {
            for (int x = bx; x < bx + BLOCK; x++) {
                double da = a[y][x] - meanA;
                double db = b[y][x] - meanB;
                varA += da * da;
                varB += db * db;
                covAB += da * db;
            }
        }
        varA /= (n - 1);
        varB /= (n - 1);
        covAB /= (n - 1);

        double numerator = (2 * meanA * meanB + C1) * (2 * covAB + C2);
        double denominator = (meanA * meanA + meanB * meanB + C1) * (varA + varB + C2);
        return numerator / denominator;
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        Image scaled = src.getScaledInstance(w, h, Image.SCALE_SMOOTH);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.getGraphics().drawImage(scaled, 0, 0, null);
        return out;
    }

    private static double[][] toGrayscale(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        double[][] gray = new double[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Color c = new Color(img.getRGB(x, y));
                gray[y][x] = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
            }
        }
        return gray;
    }
}
