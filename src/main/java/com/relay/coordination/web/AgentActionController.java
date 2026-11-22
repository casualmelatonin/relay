package com.relay.coordination.web;

import com.relay.coordination.api.dto.*;
import com.relay.coordination.domain.ItemType;
import com.relay.coordination.service.CoordinationService;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Server-form command adapter. Domain rules remain in CoordinationService. */
@Controller
public final class AgentActionController {
  private final CoordinationService service;
  private final ViewSupport ui;

  public AgentActionController(CoordinationService service, ViewSupport ui) {
    this.service = service;
    this.ui = ui;
  }

  @PostMapping("/ui/agents")
  public String register(
      @RequestParam Map<String, String> form, Authentication auth, RedirectAttributes redirect) {
    try {
      service.registerAgent(
          new RegisterAgentRequest(
              required(form, "name", 80),
              required(form, "role", 160),
              text(form, "subject").isBlank() ? auth.getName() : required(form, "subject", 200),
              ui.safeColor(form.get("color"))),
          auth.getName(),
          admin(auth));
      redirect.addFlashAttribute(
          "success", "Agent identity registered. Connect its own runtime to start working.");
      return "redirect:/?view=agents";
    } catch (RuntimeException ex) {
      redirect.addFlashAttribute("error", message(ex));
      redirect.addFlashAttribute("form", new HashMap<>(form));
      return "redirect:/agents/new";
    }
  }

  @PostMapping("/ui/agents/{id}/heartbeat")
  public String heartbeat(@PathVariable UUID id, Authentication auth, RedirectAttributes redirect) {
    try {
      List<ClaimProof> proofs =
          service.snapshot().items().stream()
              .filter(i -> id.equals(i.claimantId()) && ui.leased(i))
              .map(i -> new ClaimProof(i.id(), i.claimToken()))
              .toList();
      service.heartbeat(id, new HeartbeatRequest(proofs), auth.getName(), admin(auth));
      redirect.addFlashAttribute("success", "Heartbeat recorded and active claims renewed.");
    } catch (RuntimeException ex) {
      redirect.addFlashAttribute("error", message(ex));
    }
    return "redirect:/?view=agents";
  }

  private boolean admin(Authentication auth) {
    return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
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
