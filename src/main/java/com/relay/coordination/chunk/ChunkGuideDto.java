package com.relay.coordination.chunk;

public record ChunkGuideDto(
    long itemId,
    long version,
    String purpose,
    String learningGoal,
    String steps,
    String reflection) {
  public static ChunkGuideDto empty(long itemId) {
    return new ChunkGuideDto(itemId, -1, "", "", "", "");
  }
}
