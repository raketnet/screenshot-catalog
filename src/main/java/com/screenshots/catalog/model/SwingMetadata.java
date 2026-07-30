package com.screenshots.catalog.model;

public record SwingMetadata(
        String windowClass,
        String theme,
        String plafVersion,
        String locale,
        Integer screenWidth,
        Integer screenHeight
) {
}
