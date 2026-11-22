package com.relay.coordination.api.dto;

import com.relay.coordination.domain.ItemType;
import com.relay.coordination.domain.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreateItemRequest(
    @NotBlank @Size(max = 240) String title,
    @NotNull ItemType type,
    @Positive Long parentId,
    Priority priority,
    UUID assigneeId,
    @Size(max = 8000) String description,
    @Size(max = 8000) String acceptance,
    @Size(max = 50) List<@NotBlank @Size(max = 100) String> labels,
    @Size(max = 50) List<@NotNull @Positive Long> dependencies,
    @Size(max = 12) String keyPrefix) {}
