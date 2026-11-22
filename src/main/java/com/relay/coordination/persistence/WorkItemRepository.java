package com.relay.coordination.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkItemRepository extends JpaRepository<WorkItem, Long> {
  List<WorkItem> findAllByOrderByIdAsc();
}
