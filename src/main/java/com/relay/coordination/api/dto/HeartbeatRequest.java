package com.relay.coordination.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record HeartbeatRequest(@NotNull @Size(max = 100) List<@NotNull @Valid ClaimProof> claims) {}
