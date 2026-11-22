package com.relay.coordination.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketSequenceRepository extends JpaRepository<TicketSequence, Long> {
  boolean existsByPrefix(String prefix);
}
