package com.relay.coordination.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.coordination.api.dto.*;
import com.relay.coordination.domain.DomainException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Stateless MCP JSON-RPC transport. Coordination rules are shared with the REST and UI paths. */
@RestController
public class McpController {
  private static final Logger LOG = LoggerFactory.getLogger(McpController.class);
  private final ObjectMapper mapper;
  private final McpToolExecutor executor;

  public McpController(ObjectMapper mapper, McpToolExecutor executor) {
    this.mapper = mapper;
    this.executor = executor;
  }

  @PostMapping(value = "/mcp", consumes = "application/json", produces = "application/json")
  public ResponseEntity<?> rpc(@RequestBody String raw) {
    if (raw.length() > 131072) return ResponseEntity.status(413).build();
    JsonNode request;
    try {
      request = mapper.readTree(raw);
    } catch (JsonProcessingException invalid) {
      return error(null, -32700, "Parse error");
    }
    JsonNode id = request == null ? null : request.get("id");
    if (request == null
        || !request.isObject()
        || !"2.0".equals(request.path("jsonrpc").asText())
        || !request.path("method").isTextual()) return error(id, -32600, "Invalid Request");
    String method = request.path("method").asText();
    if (id == null) {
      return ResponseEntity.accepted().build();
    }
    if (method.equals("tools/call")) return call(id, request.path("params"));
    return switch (method) {
      case "server/discover" ->
          result(
              id,
              Map.of(
                  "supportedVersions",
                  List.of("2026-07-28", "2025-06-18", "2025-03-26"),
                  "capabilities",
                  Map.of("tools", Map.of())));
      case "initialize" -> {
        String requested = request.path("params").path("protocolVersion").asText("2025-03-26");
        String version =
            Set.of("2026-07-28", "2025-06-18", "2025-03-26").contains(requested)
                ? requested
                : "2025-03-26";
        yield result(
            id,
            Map.of(
                "protocolVersion",
                version,
                "capabilities",
                Map.of("tools", Map.of()),
                "serverInfo",
                Map.of("name", "relay-coordination", "version", "1.0.0"),
                "instructions",
                "Register an agent profile. Inspect work, claim before execution, renew leases with claimToken, and leave output evidence before review/completion. Assignment never launches a process."));
      }
      case "ping" -> result(id, Map.of());
      case "tools/list" -> result(id, Map.of("tools", ToolCatalog.tools()));
      default -> error(id, -32601, "Method not found");
    };
  }

  private ResponseEntity<?> call(JsonNode id, JsonNode params) {
    String name = params.path("name").asText();
    JsonNode args = params.path("arguments");
    if (args.isMissingNode()) args = mapper.createObjectNode();
    if (!args.isObject()) return error(id, -32602, "Tool arguments must be an object.");
    try {
      Object response = executor.execute(name, args);
      return result(
          id,
          Map.of(
              "content",
              List.of(Map.of("type", "text", "text", mapper.writeValueAsString(response))),
              "isError",
              false));
    } catch (DomainException e) {
      return result(id, toolError(e.code(), e.getMessage()));
    } catch (IllegalArgumentException e) {
      return result(
          id,
          toolError(
              "invalid_arguments", "Use the documented tool fields, types, and required values."));
    } catch (Exception e) {
      LOG.error("MCP operation failed for tool {}", name, e);
      return result(
          id,
          toolError(
              "internal_error", "The operation could not be completed. Retry with current state."));
    }
  }

  private Map<String, Object> toolError(String code, String message) {
    return Map.of(
        "content", List.of(Map.of("type", "text", "text", code + ": " + message)), "isError", true);
  }

  private ResponseEntity<?> result(JsonNode id, Object value) {
    var response = new java.util.LinkedHashMap<String, Object>();
    response.put("jsonrpc", "2.0");
    response.put("id", id);
    response.put("result", value);
    return ResponseEntity.ok(response);
  }

  private ResponseEntity<?> error(JsonNode id, int code, String message) {
    var response = new java.util.LinkedHashMap<String, Object>();
    response.put("jsonrpc", "2.0");
    response.put("id", id);
    response.put("error", Map.of("code", code, "message", message));
    return ResponseEntity.ok(response);
  }
}
