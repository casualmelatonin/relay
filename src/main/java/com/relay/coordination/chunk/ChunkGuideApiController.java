package com.relay.coordination.chunk;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/items/{id}/guide")
public class ChunkGuideApiController {
  private final ChunkGuideService guides;

  public ChunkGuideApiController(ChunkGuideService guides) {
    this.guides = guides;
  }

  @GetMapping
  public ChunkGuideDto get(@PathVariable long id) {
    return guides.get(id);
  }

  @PutMapping
  public ChunkGuideDto save(
      @PathVariable long id, @Valid @RequestBody ChunkGuideRequest request, Authentication actor) {
    return guides.save(id, request, actor.getName());
  }
}
