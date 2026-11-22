package com.relay.coordination.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface WorkspaceLockRepository extends JpaRepository<WorkspaceLock, Integer> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select workspaceLock from WorkspaceLock workspaceLock where workspaceLock.id = 1")
  Optional<WorkspaceLock> acquire();
}
