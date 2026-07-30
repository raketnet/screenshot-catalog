package com.screenshots.catalog.model;

/**
 * PENDING - not yet reviewed by anyone (the default for every change).
 * APPROVED - a human looked at this and confirmed it's an intentional change,
 *            not a bug. Timeline/report should stop flagging it as alarming
 *            regardless of how large the pixel diff is.
 * REJECTED - a human looked at this and confirmed it's a real problem. Stays
 *            visually flagged even if the raw diff score is small, since a
 *            human explicitly said "this is wrong."
 */
public enum ChangeStatus {
    PENDING, APPROVED, REJECTED
}
