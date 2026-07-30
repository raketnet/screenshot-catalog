package com.screenshots.catalog.service;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Perceptual hashing (pHash) via the DCT method:
 *   1. Downscale to a small fixed grid (default 32x32) and grayscale it.
 *   2. Run a 2D discrete cosine transform.
 *   3. Keep the low-frequency 8x8 block (dropping the DC term).
 *   4. Set each hash bit based on whether that coefficient is above the median.
 *
 * Because the image is always resized to a fixed grid first, this works even
 * when comparing two screenshots of different resolutions - unlike a raw
 * pixel diff, it survives things like DPI scaling or minor recompression,
 * while still flagging real layout/content changes.
 */
public final class PerceptualHash {

    private static final int SAMPLE_SIZE = 32;
    private static final int HASH_DIM = 8; // final hash is HASH_DIM*HASH_DIM - 1 bits
    public static final int HASH_BITS = HASH_DIM * HASH_DIM - 1;

    private PerceptualHash() {
    }

    /** @return a 64-ish bit hash packed into a long (top bits unused beyond HASH_BITS) */
    public static long compute(BufferedImage image) {
        double[][] gray = toGrayscale(resize(image, SAMPLE_SIZE, SAMPLE_SIZE));
        double[][] dct = dct2D(gray);

        double[] lowFreq = new double[HASH_BITS];
        int idx = 0;
        for (int y = 0; y < HASH_DIM; y++) {
            for (int x = 0; x < HASH_DIM; x++) {
                if (x == 0 && y == 0) continue; // skip the DC term (average brightness)
                lowFreq[idx++] = dct[y][x];
            }
        }

        double median = median(lowFreq);
        long hash = 0;
        for (double v : lowFreq) {
            hash <<= 1;
            if (v > median) hash |= 1;
        }
        return hash;
    }

    /** Hamming distance between two hashes: 0 = identical, HASH_BITS = maximally different. */
    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    public static double similarityPercent(int hammingDistance) {
        return 100.0 * (1.0 - ((double) hammingDistance / HASH_BITS));
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

    /** Separable 2D DCT-II: 1D DCT applied to rows, then to columns. */
    private static double[][] dct2D(double[][] matrix) {
        int n = matrix.length; // square input (SAMPLE_SIZE x SAMPLE_SIZE)
        double[][] rows = new double[n][n];
        for (int y = 0; y < n; y++) {
            rows[y] = dct1D(matrix[y]);
        }
        double[][] result = new double[n][n];
        for (int x = 0; x < n; x++) {
            double[] col = new double[n];
            for (int y = 0; y < n; y++) col[y] = rows[y][x];
            double[] transformed = dct1D(col);
            for (int y = 0; y < n; y++) result[y][x] = transformed[y];
        }
        return result;
    }

    private static double[] dct1D(double[] input) {
        int n = input.length;
        double[] output = new double[n];
        for (int k = 0; k < n; k++) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += input[i] * Math.cos(Math.PI / n * (i + 0.5) * k);
            }
            double scale = (k == 0) ? Math.sqrt(1.0 / n) : Math.sqrt(2.0 / n);
            output[k] = scale * sum;
        }
        return output;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2.0 : sorted[mid];
    }
}
