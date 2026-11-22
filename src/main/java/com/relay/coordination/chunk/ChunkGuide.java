package com.relay.coordination.chunk;

import jakarta.persistence.*;

/** Learning and review context is separate from the coordination state machine. */
@Entity
@Table(name = "chunk_guides")
class ChunkGuide {
  @Id private Long itemId;
  @Version private long version;

  @Column(nullable = false, length = 8000)
  private String purpose;

  @Column(nullable = false, length = 8000)
  private String learningGoal;

  @Column(nullable = false, length = 8000)
  private String steps;

  @Column(nullable = false, length = 8000)
  private String reflection;

  protected ChunkGuide() {}

  ChunkGuide(long itemId) {
    this.itemId = itemId;
  }

  void revise(ChunkGuideRequest request) {
    purpose = clean(request.purpose());
    learningGoal = clean(request.learningGoal());
    steps = clean(request.steps());
    reflection = clean(request.reflection());
  }

  ChunkGuideDto dto() {
    return new ChunkGuideDto(itemId, version, purpose, learningGoal, steps, reflection);
  }

  private String clean(String value) {
    return value == null ? "" : value.strip();
  }
}
