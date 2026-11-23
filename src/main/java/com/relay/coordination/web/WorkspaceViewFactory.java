package com.relay.coordination.web;

import com.relay.coordination.api.dto.WorkItemDto;
import com.relay.coordination.api.dto.WorkspaceSnapshot;
import com.relay.coordination.domain.ItemType;
import com.relay.coordination.domain.Priority;
import com.relay.coordination.domain.WorkStatus;
import com.relay.coordination.service.CoordinationService;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/** Builds presentation data once. Templates never query repositories or decide domain rules. */
@Component
public final class WorkspaceViewFactory {
  private final CoordinationService service;
  private final ViewSupport ui;
  private final boolean demoData;
  private final ProjectPlanPresenter projectPlans;

  public WorkspaceViewFactory(
      CoordinationService service,
      ViewSupport ui,
      ProjectPlanPresenter projectPlans,
      @Value("${relay.demo-data:false}") boolean demoData) {
    this.service = service;
    this.ui = ui;
    this.projectPlans = projectPlans;
    this.demoData = demoData;
  }

  public WorkspaceSnapshot populate(
      Model model,
      String view,
      String query,
      String type,
      String owner,
      String priority,
      String status) {
    var snapshot = service.snapshot();
    var items = snapshot.items();
    var byId = items.stream().collect(Collectors.toMap(WorkItemDto::id, Function.identity()));
    String search = query.strip().toLowerCase(Locale.ROOT);
    var filtered =
        items.stream()
            .filter(i -> !Set.of("tasks", "board").contains(view) || ui.work(i))
            .filter(
                i ->
                    search.isEmpty()
                        || (i.key()
                                + " "
                                + i.title()
                                + " "
                                + i.description()
                                + " "
                                + String.join(" ", i.labels()))
                            .toLowerCase(Locale.ROOT)
                            .contains(search))
            .filter(i -> type.isBlank() || i.type().name().equalsIgnoreCase(type))
            .filter(i -> status.isBlank() || i.status().name().equalsIgnoreCase(status))
            .filter(i -> priority.isBlank() || i.priority().name().equalsIgnoreCase(priority))
            .filter(
                i ->
                    owner.isBlank()
                        || (owner.equals("unassigned")
                            ? i.assigneeId() == null
                            : String.valueOf(i.assigneeId()).equals(owner)))
            .toList();
    Map<String, List<WorkItemDto>> columns = new LinkedHashMap<>();
    for (var state : WorkStatus.values())
      columns.put(state.name(), filtered.stream().filter(i -> i.status() == state).toList());
    var children =
        items.stream()
            .filter(i -> i.parentId() != null)
            .collect(Collectors.groupingBy(WorkItemDto::parentId));
    model.addAttribute("snapshot", snapshot);
    model.addAttribute("projectPlans", projectPlans.plans(items));
    model.addAttribute("items", items);
    model.addAttribute("filtered", filtered);
    model.addAttribute("agents", snapshot.agents());
    model.addAttribute("itemsById", byId);
    model.addAttribute(
        "agentsById",
        snapshot.agents().stream().collect(Collectors.toMap(a -> a.id(), Function.identity())));
    model.addAttribute("childrenByParent", children);
    model.addAttribute("columns", columns);
    model.addAttribute(
        "initiatives", items.stream().filter(i -> i.type() == ItemType.INITIATIVE).toList());
    model.addAttribute("activity", snapshot.activity());
    model.addAttribute("view", view);
    model.addAttribute("query", query);
    model.addAttribute("typeFilter", type);
    model.addAttribute("agentFilter", owner);
    model.addAttribute("priorityFilter", priority);
    model.addAttribute("statusFilter", status);
    model.addAttribute("types", ItemType.values());
    model.addAttribute("statuses", WorkStatus.values());
    model.addAttribute("priorities", Priority.values());
    model.addAttribute("claimAgents", snapshot.agents());
    model.addAttribute("demoData", demoData);
    return snapshot;
  }
}
