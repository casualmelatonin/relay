package com.relay.coordination.api.dto;

import com.relay.coordination.domain.ItemType;
import com.relay.coordination.domain.Priority;
import com.relay.coordination.domain.WorkStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkItemDto(
    long id,
    String key,
    String title,
    ItemType type,
    Long parentId,
    WorkStatus status,
    Priority priority,
    UUID assigneeId,
    UUID claimantId,
    UUID claimToken,
    Instant leaseUntil,
    String description,
    String acceptance,
    String output,
    List<String> labels,
    List<Long> dependencies,
    long version,
    Instant createdAt,
    Instant updatedAt) {}
