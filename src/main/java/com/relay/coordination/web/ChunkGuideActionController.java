package com.relay.coordination.web;

import com.relay.coordination.chunk.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ChunkGuideActionController {
  private final ChunkGuideService guides;

  public ChunkGuideActionController(ChunkGuideService guides) {
    this.guides = guides;
  }

  @PostMapping("/ui/items/{id}/guide")
  public String save(
      @PathVariable long id,
      @ModelAttribute ChunkGuideRequest request,
      RedirectAttributes redirect) {
    try {
      guides.save(id, request, "workspace");
      redirect.addFlashAttribute(
          "success", "Learning guide saved. Review it before you start building.");
    } catch (com.relay.coordination.domain.DomainException error) {
      redirect.addFlashAttribute("error", error.getMessage());
      redirect.addFlashAttribute("guideDraft", request);
    }
    return "redirect:/items/" + id;
  }
}
