package com.relay.coordination.web;

import com.relay.coordination.api.dto.*;
import com.relay.coordination.domain.ItemType;
import com.relay.coordination.domain.Priority;
import com.relay.coordination.domain.WorkStatus;
import com.relay.coordination.service.CoordinationService;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Server-form command adapter. Domain rules remain in CoordinationService. */
@Controller
public final class WorkItemActionController {
  private final CoordinationService service;
  private final ViewSupport ui;

  public WorkItemActionController(CoordinationService service, ViewSupport ui) {
    this.service = service;
    this.ui = ui;
  }

  @PostMapping("/ui/items")
  public String create(@RequestParam Map<String, String> form, RedirectAttributes redirect) {
    try {
      WorkItemDto item =
          service.create(
              new CreateItemRequest(
                  required(form, "title", 200),
                  ItemType.valueOf(validType(form.get("type"))),
                  longOrNull(form.get("parentId")),
                  enumOrNull(Priority.class, form.get("priority")),
                  uuidOrNull(form.get("assigneeId")),
                  text(form, "description"),
                  text(form, "acceptance"),
                  labels(form.get("labels")),
                  ids(form.get("dependencies")),
                  form.get("keyPrefix")),
              "workspace");
      redirect.addFlashAttribute(
          "success", item.key() + " created in the backlog. Assign an owner when you are ready.");
      return "redirect:/items/" + item.id();
    } catch (RuntimeException ex) {
      redirect.addFlashAttribute("error", message(ex));
      redirect.addFlashAttribute("form", new HashMap<>(form));
      return "redirect:/items/new";
    }
  }

  @PostMapping("/ui/items/{id}/update")
  public String update(
      @PathVariable long id,
      @RequestParam Map<String, String> form,
      @RequestParam(required = false) Long project,
      RedirectAttributes redirect) {
    if (project != null) redirect.addAttribute("project", project);
    try {
      service.update(
          id,
          new UpdateItemRequest(
              Long.parseLong(form.get("version")),
              required(form, "title", 200),
              longOrNull(form.get("parentId")),
              text(form, "parentId").isBlank(),
              enumOrNull(WorkStatus.class, form.get("status")),
              enumOrNull(Priority.class, form.get("priority")),
              uuidOrNull(form.get("assigneeId")),
              text(form, "assigneeId").isBlank(),
              text(form, "description"),
              text(form, "acceptance"),
              text(form, "output"),
              labels(form.get("labels")),
              ids(form.get("dependencies")),
              uuidOrNull(form.get("agentId")),
              uuidOrNull(form.get("claimToken")),
              form.get("keyPrefix")),
          "workspace");
      redirect.addFlashAttribute("success", "Changes saved.");
    } catch (RuntimeException ex) {
      redirect.addFlashAttribute(
          "error",
          message(ex)
              + " Your edits are preserved below; review them against the current version before saving.");
      redirect.addFlashAttribute("editForm", new HashMap<>(form));
    }
    return "redirect:/items/" + id;
  }

  @PostMapping("/ui/items/{id}/claim")
  public String claim(
      @PathVariable long id,
      @RequestParam UUID agentId,
      @RequestParam long version,
      @RequestParam(required = false) Long project,
      RedirectAttributes redirect) {
    if (project != null) redirect.addAttribute("project", project);
    try {
      service.claim(id, new ClaimRequest(agentId, version), "workspace");
      redirect.addFlashAttribute("success", "Execution claim acquired for 15 minutes.");
    } catch (RuntimeException ex) {
      redirect.addFlashAttribute("error", message(ex));
    }
    return "redirect:/items/" + id;
  }

  @PostMapping("/ui/items/{id}/release")
  public String release(
      @PathVariable long id,
      @RequestParam UUID agentId,
      @RequestParam long version,
      @RequestParam UUID claimToken,
      @RequestParam(required = false) Long project,
      RedirectAttributes redirect) {
    if (project != null) redirect.addAttribute("project", project);
    try {
      service.release(id, new ReleaseRequest(agentId, version, claimToken), "workspace");
      redirect.addFlashAttribute("success", "Claim released. The task is available for a handoff.");
    } catch (RuntimeException ex) {
      redirect.addFlashAttribute("error", message(ex));
    }
    return "redirect:/items/" + id;
  }

  @PostMapping("/ui/items/{id}/comments")
  public String comment(
      @PathVariable long id,
      @RequestParam String body,
      @RequestParam(required = false) Long project,
      RedirectAttributes redirect) {
    if (project != null) redirect.addAttribute("project", project);
    try {
      if (body == null || body.isBlank())
        throw new IllegalArgumentException("Write a comment before posting.");
      service.comment(id, new CommentRequest(body), "workspace");
      redirect.addFlashAttribute("success", "Comment added to the shared history.");
    } catch (RuntimeException ex) {
      redirect.addFlashAttribute("error", message(ex));
      redirect.addFlashAttribute("commentBody", body);
    }
    return "redirect:/items/" + id;
  }

  private String validType(String type) {
    if (type == null || type.isBlank()) return "TASK";
    try {
      return ItemType.valueOf(type.toUpperCase(Locale.ROOT)).name();
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Choose a valid work item type.");
    }
  }

  private <T extends Enum<T>> T enumOrNull(Class<T> type, String value) {
    return value == null || value.isBlank()
        ? null
        : Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
  }

  private String required(Map<String, String> form, String key, int max) {
    String value = text(form, key).trim();
    if (value.isBlank()) throw new IllegalArgumentException(ui.label(key) + " is required.");
    if (value.length() > max)
      throw new IllegalArgumentException(
          ui.label(key) + " must be at most " + max + " characters.");
    return value;
  }

  private String text(Map<String, String> form, String key) {
    return Objects.requireNonNullElse(form.get(key), "");
  }

  private Long longOrNull(String value) {
    return value == null || value.isBlank() ? null : Long.valueOf(value.trim());
  }

  private UUID uuidOrNull(String value) {
    return value == null || value.isBlank() ? null : UUID.fromString(value.trim());
  }

  private List<String> labels(String value) {
    return value == null || value.isBlank()
        ? List.of()
        : Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .distinct()
            .toList();
  }

  private List<Long> ids(String value) {
    if (value == null || value.isBlank()) return List.of();
    Map<String, Long> keys =
        service.snapshot().items().stream()
            .collect(Collectors.toMap(i -> i.key().toUpperCase(Locale.ROOT), WorkItemDto::id));
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .map(
            s -> {
              Long id = keys.get(s.toUpperCase(Locale.ROOT));
              if (id != null) return id;
              try {
                return Long.valueOf(s);
              } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                    "Unknown dependency key: " + s + ". Use work item keys separated by commas.");
              }
            })
        .distinct()
        .toList();
  }

  private String message(RuntimeException ex) {
    return ex instanceof com.relay.coordination.domain.DomainException
            || ex instanceof IllegalArgumentException
        ? Objects.requireNonNullElse(ex.getMessage(), "Check the form values and try again.")
        : "The action could not be completed. Refresh the page and try again.";
  }
}
