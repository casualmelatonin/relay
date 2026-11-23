package com.relay.coordination.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record DeleteAgentRequest(@NotNull UUID agentId) {}
