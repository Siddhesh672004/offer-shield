package com.offershield.review;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Body of {@code POST /api/reviews}: which of the student's own analysed submissions to send to their TPO. */
public record CreateReviewRequest(@NotNull UUID submissionId) {
}
