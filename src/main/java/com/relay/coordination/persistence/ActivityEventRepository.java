package com.relay.coordination.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityEventRepository extends JpaRepository<ActivityEvent, UUID> {
  List<ActivityEvent> findTop200ByOrderByCreatedAtDescIdDesc();
}
