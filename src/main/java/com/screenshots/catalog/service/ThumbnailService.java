package com.screenshots.catalog.service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Downscaled previews for grid/timeline browsing, so those views don't
 * transfer and decode full-resolution screenshots just to show a small card.
 * Full inspection (the lightbox, "open full size" links, and the compare
 * view) still goes through /api/images/{id} directly - this is only for
 * overview browsing.
 *
 * Cached in memory, keyed by SHA-256 rather than screenshot id. That's
 * deliberate: a rescan can update a screenshot's row (same id) with new file
 * content (different hash), and an id-keyed cache would then silently serve
 * a stale thumbnail for the new content. Keying by content hash means the
 * cache can never go stale - same bytes in, same thumbnail out, forever -
 * and as a side effect, exact-duplicate files (already tracked via SHA-256
 * elsewhere in this app) automatically share one cached thumbnail.
 */
public class ThumbnailService {

    private static final int DEFAULT_MAX_DIMENSION = 480;
    private static final int MAX_ALLOWED_DIMENSION = 800;
    private static final int CACHE_CAPACITY = 300;

    // Simple bounded LRU: LinkedHashMap in access order, evicting the oldest entry past capacity.
    private final Map<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > CACHE_CAPACITY;
        }
    };

    public static int clampDimension(Integer requested) {
        if (requested == null) return DEFAULT_MAX_DIMENSION;
        return Math.max(32, Math.min(MAX_ALLOWED_DIMENSION, requested));
    }

    /**
     * @param filePath   path to the full-resolution source image on disk
     * @param sha256     the screenshot's content hash - the cache key
     * @param maxDimension the longer side is scaled down to at most this many pixels;
     *                     images already smaller than this are returned as-is (re-encoded, not upscaled)
     * @return PNG-encoded thumbnail bytes
     */
    public byte[] getThumbnail(Path filePath, String sha256, int maxDimension) throws IOException {
        String cacheKey = sha256 + ":" + maxDimension;

        synchronized (cache) {
            byte[] cached = cache.get(cacheKey);
            if (cached != null) return cached;
        }

        BufferedImage original = ImageIO.read(filePath.toFile());
        if (original == null) {
            throw new IOException("Could not decode image: " + filePath);
        }

        byte[] thumbnail = encode(resize(original, maxDimension));

        synchronized (cache) {
            cache.put(cacheKey, thumbnail);
        }
        return thumbnail;
    }

    private BufferedImage resize(BufferedImage src, int maxDimension) {
        int width = src.getWidth();
        int height = src.getHeight();
        int longerSide = Math.max(width, height);

        if (longerSide <= maxDimension) {
            return src; // already small enough - don't upscale, just re-encode as-is
        }

        double scale = (double) maxDimension / longerSide;
        int newWidth = Math.max(1, (int) Math.round(width * scale));
        int newHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return resized;
    }

    private byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }

    /** Current number of distinct (hash, size) thumbnails held in memory - exposed for diagnostics/tests. */
    public int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }
}
