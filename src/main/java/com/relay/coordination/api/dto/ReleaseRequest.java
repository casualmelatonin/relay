package com.relay.coordination.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record ReleaseRequest(
    @NotNull UUID agentId, @NotNull @PositiveOrZero Long version, UUID claimToken) {}
