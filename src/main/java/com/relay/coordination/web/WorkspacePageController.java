package com.relay.coordination.web;

import com.relay.coordination.domain.ItemType;
import com.relay.coordination.service.CoordinationService;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * Read-only page routes. Mutation adapters, view preparation, and formatting are separate modules.
 */
@Controller
public final class WorkspacePageController {
  private static final Set<String> VIEWS =
      Set.of("projects", "tasks", "board", "initiatives", "agents", "activity", "tools");
  private final WorkspaceViewFactory views;
  private final CoordinationService service;

  public WorkspacePageController(WorkspaceViewFactory views, CoordinationService service) {
    this.views = views;
    this.service = service;
  }

  @GetMapping("/")
  public String workspace(
      @RequestParam(defaultValue = "projects") String view,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String type,
      @RequestParam(defaultValue = "") String agent,
      @RequestParam(defaultValue = "") String priority,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(required = false) Long project,
      Model model) {
    views.populate(model, VIEWS.contains(view) ? view : "tasks", q, type, agent, priority, status);
    model.addAttribute("projectFilter", project);
    return "workspace";
  }

  @GetMapping("/items/{id}")
  public String item(@PathVariable long id, Model model) {
    var item = service.getItem(id);
    views.populate(model, "tasks", "", "", "", "", "");
    model.addAttribute("selected", item);
    if (!model.containsAttribute("editForm")) model.addAttribute("editForm", Map.of());
    return "item-detail";
  }

  @GetMapping("/items/new")
  public String newItem(
      @RequestParam(defaultValue = "TASK") ItemType type,
      @RequestParam(required = false) Long parent,
      Model model) {
    views.populate(model, "tasks", "", "", "", "", "");
    if (!model.containsAttribute("form")) {
      Map<String, String> form = new HashMap<>();
      form.put("type", type.name());
      form.put("priority", "MEDIUM");
      form.put("parentId", parent == null ? "" : parent.toString());
      model.addAttribute("form", form);
    }
    return "new-item";
  }

  @GetMapping("/agents/new")
  public String newAgent(Model model) {
    views.populate(model, "agents", "", "", "", "", "");
    if (!model.containsAttribute("form")) model.addAttribute("form", Map.of("color", "#5265dd"));
    return "new-agent";
  }
}
