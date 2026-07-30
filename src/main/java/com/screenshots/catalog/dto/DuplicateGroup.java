package com.screenshots.catalog.dto;

import com.screenshots.catalog.model.Screenshot;

import java.util.List;

public record DuplicateGroup(
        String sha256,
        int count,
        List<Screenshot> screenshots
) {
}
