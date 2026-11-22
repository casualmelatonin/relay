package com.relay.coordination.api.dto;

import com.relay.coordination.domain.Priority;
import com.relay.coordination.domain.WorkStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record UpdateItemRequest(
    @NotNull @PositiveOrZero Long version,
    @Size(min = 1, max = 240) String title,
    @Positive Long parentId,
    boolean clearParent,
    WorkStatus status,
    Priority priority,
    UUID assigneeId,
    boolean clearAssignee,
    @Size(max = 8000) String description,
    @Size(max = 8000) String acceptance,
    @Size(max = 8000) String output,
    @Size(max = 50) List<@NotBlank @Size(max = 100) String> labels,
    @Size(max = 50) List<@NotNull @Positive Long> dependencies,
    UUID agentId,
    UUID claimToken) {}
