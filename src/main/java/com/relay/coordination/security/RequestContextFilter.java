package com.relay.coordination.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds correlation without logging credentials and rejects browser cross-origin API mutations. */
@Component("relayRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestContextFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader("X-Request-Id");
    String requestId =
        supplied != null && supplied.matches("[A-Za-z0-9-]{8,64}")
            ? supplied
            : UUID.randomUUID().toString();
    MDC.put("requestId", requestId);
    response.setHeader("X-Request-Id", requestId);
    try {
      String path = request.getRequestURI();
      boolean api = path.startsWith("/api/") || path.equals("/mcp");
      if (api && !request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")) {
        String origin = request.getHeader("Origin");
        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))
            || (origin != null && !sameOrigin(origin, request))) {
          response.sendError(403, "Cross-origin actions are not supported.");
          return;
        }
        if (request.getContentLengthLong() > 131072) {
          response.sendError(413, "Request exceeds 128 KiB.");
          return;
        }
      }
      chain.doFilter(request, response);
    } finally {
      MDC.remove("requestId");
    }
  }

  private boolean sameOrigin(String origin, HttpServletRequest request) {
    try {
      URI uri = URI.create(origin);
      int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
      return uri.getScheme().equalsIgnoreCase(request.getScheme())
          && uri.getHost().equalsIgnoreCase(request.getServerName())
          && port == request.getServerPort();
    } catch (IllegalArgumentException | NullPointerException invalidOrigin) {
      return false;
    }
  }
}
