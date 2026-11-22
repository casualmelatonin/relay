package com.relay.coordination.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persistent counter for a project; scope zero is reserved for standalone work. */
@Entity
@Table(name = "ticket_sequences")
public class TicketSequence {
  @Id
  @Column(name = "scope_id")
  private Long scopeId;

  @Column(nullable = false, unique = true, length = 12)
  private String prefix;

  @Column(name = "next_number", nullable = false)
  private long nextNumber;

  protected TicketSequence() {}

  public TicketSequence(long scopeId, String prefix, long nextNumber) {
    this.scopeId = scopeId;
    this.prefix = prefix;
    this.nextNumber = nextNumber;
  }

  public String getPrefix() {
    return prefix;
  }

  public void rename(String prefix) {
    this.prefix = prefix;
  }

  public long allocate() {
    return nextNumber++;
  }
}
