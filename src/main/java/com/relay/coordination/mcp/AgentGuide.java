package com.relay.coordination.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Loads the repository's onboarding guide bundled into the application at build time. */
@Component
public final class AgentGuide {
  private final String content;

  public AgentGuide() throws IOException {
    content = new ClassPathResource("relay/agents.md").getContentAsString(StandardCharsets.UTF_8);
  }

  public Map<String, String> read() {
    return Map.of("format", "markdown", "content", content);
  }
}
