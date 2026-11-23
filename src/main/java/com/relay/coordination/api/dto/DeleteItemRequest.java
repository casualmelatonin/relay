package com.relay.coordination.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record DeleteItemRequest(@NotNull @PositiveOrZero Long version) {}
