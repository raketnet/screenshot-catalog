package com.screenshots.catalog.service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The image diffing layer: combines three complementary comparison signals
 * for two screenshots.
 *
 *  - pixelDifferencePercentage: exact pixel-level change. Only computed when
 *    dimensions match; very sensitive to any change, including harmless
 *    anti-aliasing/subpixel noise.
 *  - pHashSimilarityPercent: perceptual hash similarity. Survives resolution
 *    differences and minor rendering noise; good "does this look basically
 *    the same" signal, always computable.
 *  - ssimScore: structural similarity (0-1, 1 = identical structure). Good
 *    middle ground - more tolerant than raw pixel diff, more sensitive to
 *    real layout change than pHash. Approximate (right image is resized to
 *    match left) when dimensions differ.
 */
public class ImageDiffService {

    private static final int CHANGE_THRESHOLD = 30; // per-pixel |dR|+|dG|+|dB| to count as "changed"

    public record DiffResult(
            boolean dimensionsMatch,
            Double pixelDifferencePercentage, // null when dimensions differ
            int pHashDistance,
            double pHashSimilarityPercent,
            double ssimScore,
            BufferedImage diffImage,          // null when dimensions differ
            String note
    ) {
    }

    public DiffResult diff(Path leftPath, Path rightPath) throws IOException {
        BufferedImage left = ImageIO.read(leftPath.toFile());
        BufferedImage right = ImageIO.read(rightPath.toFile());

        if (left == null || right == null) {
            throw new IOException("Could not decode one of the images (%s / %s)".formatted(leftPath, rightPath));
        }

        long leftHash = PerceptualHash.compute(left);
        long rightHash = PerceptualHash.compute(right);
        int hashDistance = PerceptualHash.hammingDistance(leftHash, rightHash);
        double hashSimilarity = PerceptualHash.similarityPercent(hashDistance);
        double ssim = SsimCalculator.compute(left, right);

        boolean dimensionsMatch = left.getWidth() == right.getWidth() && left.getHeight() == right.getHeight();

        if (!dimensionsMatch) {
            return new DiffResult(false, null, hashDistance, hashSimilarity, ssim, null,
                    "Dimensions differ (%dx%d vs %dx%d): pixel diff skipped, SSIM computed on a resized copy"
                            .formatted(left.getWidth(), left.getHeight(), right.getWidth(), right.getHeight()));
        }

        int width = left.getWidth();
        int height = left.getHeight();
        BufferedImage diffImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        long changedPixels = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = left.getRGB(x, y);
                int b = right.getRGB(x, y);

                int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
                int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
                int db = Math.abs((a & 0xFF) - (b & 0xFF));

                if (dr + dg + db > CHANGE_THRESHOLD) {
                    changedPixels++;
                    diffImage.setRGB(x, y, 0xFFFF00FF); // magenta highlight
                } else {
                    int gray = (((a >> 16) & 0xFF) + ((a >> 8) & 0xFF) + (a & 0xFF)) / 3;
                    int dimmed = gray / 3;
                    diffImage.setRGB(x, y, 0xFF000000 | (dimmed << 16) | (dimmed << 8) | dimmed);
                }
            }
        }

        double pixelDiffPercent = (changedPixels * 100.0) / ((double) width * height);
        return new DiffResult(true, pixelDiffPercent, hashDistance, hashSimilarity, ssim, diffImage, null);
    }
}
