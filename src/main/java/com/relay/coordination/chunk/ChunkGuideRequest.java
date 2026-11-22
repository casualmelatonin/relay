package com.relay.coordination.chunk;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChunkGuideRequest(
    @NotNull @Min(-1) Long version,
    @Size(max = 8000) String purpose,
    @Size(max = 8000) String learningGoal,
    @Size(max = 8000) String steps,
    @Size(max = 8000) String reflection) {}
