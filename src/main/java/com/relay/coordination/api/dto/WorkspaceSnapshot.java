package com.relay.coordination.api.dto;

import java.util.List;

public record WorkspaceSnapshot(
    List<WorkItemDto> items, List<AgentDto> agents, List<ActivityDto> activity) {}
