package com.relay.coordination.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record ClaimProof(@Positive long itemId, @NotNull UUID claimToken) {}
