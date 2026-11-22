package com.relay.coordination.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "agent_profiles")
public class AgentProfile {
  @Id private UUID id;

  @Column(nullable = false, length = 80)
  private String name;

  @Column(name = "normalized_name", nullable = false, unique = true, length = 160)
  private String normalizedName;

  @Column(nullable = false, length = 160)
  private String role;

  @Column(nullable = false, length = 200)
  private String subject;

  @Column(nullable = false, length = 7)
  private String color;

  @Column(name = "last_seen", nullable = false)
  private Instant lastSeen;

  protected AgentProfile() {}

  public AgentProfile(String name, String role, String subject, String color, Instant now) {
    this.id = UUID.randomUUID();
    this.name = name;
    this.normalizedName = name.toLowerCase(Locale.ROOT);
    this.role = role;
    this.subject = subject;
    this.color = color;
    this.lastSeen = now;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getRole() {
    return role;
  }

  public String getSubject() {
    return subject;
  }

  public String getColor() {
    return color;
  }

  public Instant getLastSeen() {
    return lastSeen;
  }

  public void seenAt(Instant now) {
    lastSeen = now;
  }
}
