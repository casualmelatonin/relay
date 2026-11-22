package com.relay.coordination.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterAgentRequest(
    @NotBlank @Size(max = 80) String name,
    @NotBlank @Size(max = 160) String role,
    @Size(max = 200) String subject,
    @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String color) {}
