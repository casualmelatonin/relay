package com.relay.coordination.persistence;

import com.relay.coordination.domain.ItemType;
import com.relay.coordination.domain.Priority;
import com.relay.coordination.domain.WorkStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "work_items")
public class WorkItem {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Version private long version;

  @Column(name = "key_prefix", length = 12)
  private String keyPrefix;

  @Column(name = "ticket_prefix", nullable = false, length = 12)
  private String ticketPrefix;

  @Column(name = "ticket_number", nullable = false)
  private long ticketNumber;

  @Column(nullable = false, length = 240)
  private String title;

  @Enumerated(EnumType.STRING)
  @Column(name = "item_type", nullable = false, length = 20)
  private ItemType type;

  @Column(name = "parent_id")
  private Long parentId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private WorkStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Priority priority;

  @Column(name = "assignee_id")
  private UUID assigneeId;

  @Column(name = "claimant_id")
  private UUID claimantId;

  @Column(name = "claim_token")
  private UUID claimToken;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(nullable = false, length = 8000)
  private String description;

  @Column(nullable = false, length = 8000)
  private String acceptance;

  @Column(nullable = false, length = 8000)
  private String output;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @ElementCollection
  @CollectionTable(name = "work_item_labels", joinColumns = @JoinColumn(name = "work_item_id"))
  @Column(name = "label", nullable = false, length = 100)
  @BatchSize(size = 50)
  private Set<String> labels = new LinkedHashSet<>();

  @ElementCollection
  @CollectionTable(
      name = "work_item_dependencies",
      joinColumns = @JoinColumn(name = "work_item_id"))
  @Column(name = "prerequisite_id", nullable = false)
  @BatchSize(size = 50)
  private Set<Long> dependencies = new LinkedHashSet<>();

  protected WorkItem() {}

  public WorkItem(
      String title,
      ItemType type,
      Long parentId,
      Priority priority,
      UUID assigneeId,
      String description,
      String acceptance,
      Set<String> labels,
      Set<Long> dependencies,
      Instant now) {
    this.title = title;
    this.type = type;
    this.parentId = parentId;
    this.status = WorkStatus.BACKLOG;
    this.priority = priority;
    this.assigneeId = assigneeId;
    this.description = description;
    this.acceptance = acceptance;
    this.output = "";
    this.labels.addAll(labels);
    this.dependencies.addAll(dependencies);
    this.createdAt = now;
    this.updatedAt = now;
  }

  public Long getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public String getKeyPrefix() {
    return keyPrefix;
  }

  public void setKeyPrefix(String keyPrefix) {
    this.keyPrefix = keyPrefix;
  }

  public String getTicketPrefix() {
    return ticketPrefix;
  }

  public long getTicketNumber() {
    return ticketNumber;
  }

  public void assignTicketKey(String prefix, long number) {
    this.ticketPrefix = prefix;
    this.ticketNumber = number;
  }

  public String getTitle() {
    return title;
  }

  public ItemType getType() {
    return type;
  }

  public Long getParentId() {
    return parentId;
  }

  public WorkStatus getStatus() {
    return status;
  }

  public Priority getPriority() {
    return priority;
  }

  public UUID getAssigneeId() {
    return assigneeId;
  }

  public UUID getClaimantId() {
    return claimantId;
  }

  public UUID getClaimToken() {
    return claimToken;
  }

  public Instant getLeaseUntil() {
    return leaseUntil;
  }

  public String getDescription() {
    return description;
  }

  public String getAcceptance() {
    return acceptance;
  }

  public String getOutput() {
    return output;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Set<String> getLabels() {
    return Collections.unmodifiableSet(labels);
  }

  public Set<Long> getDependencies() {
    return Collections.unmodifiableSet(dependencies);
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public void setParentId(Long parentId) {
    this.parentId = parentId;
  }

  public void setStatus(WorkStatus status) {
    this.status = status;
  }

  public void setPriority(Priority priority) {
    this.priority = priority;
  }

  public void setAssigneeId(UUID assigneeId) {
    this.assigneeId = assigneeId;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public void setAcceptance(String acceptance) {
    this.acceptance = acceptance;
  }

  public void setOutput(String output) {
    this.output = output;
  }

  public void touch(Instant now) {
    this.updatedAt = now;
  }

  public void replaceLabels(Set<String> next) {
    labels.clear();
    labels.addAll(next);
  }

  public void replaceDependencies(Set<Long> next) {
    dependencies.clear();
    dependencies.addAll(next);
  }

  public boolean hasActiveClaim(Instant now) {
    return claimantId != null
        && claimToken != null
        && leaseUntil != null
        && leaseUntil.isAfter(now);
  }

  public void claim(UUID agentId, UUID token, Instant until) {
    this.assigneeId = agentId;
    this.claimantId = agentId;
    this.claimToken = token;
    this.leaseUntil = until;
    this.status = WorkStatus.IN_PROGRESS;
  }

  public void renewLease(Instant until) {
    this.leaseUntil = until;
  }

  public void clearClaim() {
    this.claimantId = null;
    this.claimToken = null;
    this.leaseUntil = null;
  }
}
