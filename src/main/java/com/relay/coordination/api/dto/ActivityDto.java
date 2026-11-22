package com.relay.coordination.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ActivityDto(UUID id, Long itemId, String actor, String message, Instant createdAt) {}
