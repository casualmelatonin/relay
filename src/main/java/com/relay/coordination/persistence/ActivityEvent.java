package com.relay.coordination.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "activity_events")
public class ActivityEvent {
  @Id private UUID id;

  @Column(name = "item_id", updatable = false)
  private Long itemId;

  @Column(nullable = false, updatable = false, length = 200)
  private String actor;

  @Column(nullable = false, updatable = false, length = 8000)
  private String message;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ActivityEvent() {}

  public ActivityEvent(Long itemId, String actor, String message, Instant now) {
    this.id = UUID.randomUUID();
    this.itemId = itemId;
    this.actor = actor;
    this.message = message;
    this.createdAt = now;
  }

  public UUID getId() {
    return id;
  }

  public Long getItemId() {
    return itemId;
  }

  public String getActor() {
    return actor;
  }

  public String getMessage() {
    return message;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
