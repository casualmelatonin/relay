package com.relay.coordination.chunk;

import com.relay.coordination.domain.DomainException;
import com.relay.coordination.domain.ItemType;
import com.relay.coordination.persistence.*;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ChunkGuideService {
  private final ChunkGuideRepository guides;
  private final WorkItemRepository items;
  private final WorkspaceLockRepository locks;
  private final ActivityEventRepository events;
  private final Clock clock;

  public ChunkGuideService(
      ChunkGuideRepository guides,
      WorkItemRepository items,
      WorkspaceLockRepository locks,
      ActivityEventRepository events,
      Clock clock) {
    this.guides = guides;
    this.items = items;
    this.locks = locks;
    this.events = events;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ChunkGuideDto get(long itemId) {
    if (!items.existsById(itemId)) throw new DomainException(404, "not_found", "Chunk not found.");
    return guides
        .findById(itemId)
        .map(ChunkGuide::dto)
        .orElseGet(() -> ChunkGuideDto.empty(itemId));
  }

  public ChunkGuideDto save(long itemId, ChunkGuideRequest request, String actor) {
    locks.acquire().orElseThrow(() -> new IllegalStateException("Missing workspace lock."));
    var item =
        items
            .findById(itemId)
            .orElseThrow(() -> new DomainException(404, "not_found", "Chunk not found."));
    if (item.getType() != ItemType.TASK && item.getType() != ItemType.STORY)
      throw new DomainException(400, "not_a_chunk", "Learning guides belong to a story or chunk.");
    var guide = guides.findById(itemId).orElse(null);
    if (request.version() == null
        || request.version() != (guide == null ? -1 : guide.dto().version()))
      throw new DomainException(
          409, "version_conflict", "The learning guide changed. Refresh it before saving.");
    for (var value :
        new String[] {
          request.purpose(), request.learningGoal(), request.steps(), request.reflection()
        }) {
      if (value != null && value.length() > 8000)
        throw new DomainException(
            400, "invalid_request", "Keep each guide field within 8,000 characters.");
    }
    if (guide == null) guide = new ChunkGuide(itemId);
    guide.revise(request);
    guides.saveAndFlush(guide);
    events.save(
        new ActivityEvent(
            itemId,
            Objects.requireNonNull(actor),
            "Updated the chunk learning guide and review notes.",
            clock.instant()));
    return guide.dto();
  }
}
