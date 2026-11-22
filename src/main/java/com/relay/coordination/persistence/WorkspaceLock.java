package com.relay.coordination.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A database-backed mutex protecting cross-item graph invariants on every application replica. */
@Entity
@Table(name = "workspace_lock")
public class WorkspaceLock {
  @Id private Integer id;

  protected WorkspaceLock() {}

  public Integer getId() {
    return id;
  }
}
