package com.relay.coordination.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentProfileRepository extends JpaRepository<AgentProfile, UUID> {
  boolean existsByNormalizedName(String normalizedName);

  List<AgentProfile> findAllByOrderByNameAsc();
}
