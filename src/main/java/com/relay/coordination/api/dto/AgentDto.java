package com.relay.coordination.api.dto;

import java.time.Instant;
import java.util.UUID;

public record AgentDto(
    UUID id,
    String name,
    String role,
    String subject,
    String color,
    Instant lastSeen,
    String presence) {}
