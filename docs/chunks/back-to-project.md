# Return from a chunk to its project

## Outcome and definition of done

Chunks opened from a project card or project plan show “Back to project” and return to that specific plan. Direct and All chunks entry points keep “Back to chunks.” The originating project survives editing, saves, comments, claims, and related-ticket links.

## Why this matters and what to learn

The back link should match the developer's navigation path. A chunk's parent may be a milestone, story, or another task, so a parent link alone does not represent where the developer came from. Learn to carry explicit navigation context through a server-rendered POST/redirect/GET flow without storing it in the domain model or relying on browser history.

## Small steps

1. Add the originating project ID to project-card and plan links.
2. Resolve only existing project records for the return destination; use the chunk list as the fallback.
3. Carry context in item links and hidden form fields, then preserve it in redirects.
4. Verify project and direct-entry paths, successful and rejected saves, comments, and claim/release actions.

## Review notes

Navigation context is a presentation concern. It does not change parent relationships, work ownership, or permissions. The return URL is built from an internal route and a numeric project ID rather than an arbitrary supplied URL. A stale or non-project ID falls back to the chunk list.
