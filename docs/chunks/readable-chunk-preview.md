# Readable chunk preview

## Outcome and scope

Opening a ticket shows its complete description, definition of done, and result as readable page sections. The definition of done is that long content needs only normal page scrolling, section links reach the relevant content, and editing still preserves drafts after rejected saves. This chunk changes the web presentation and page routing.

## Why this matters

Reading a chunk is the first step in understanding the work. Short edit boxes hide context and make the developer navigate several independent scroll areas before they can review the outcome.

## Concept being learned

Separate a reading view from an editing form while keeping one stored work item. The page controller selects the view, Thymeleaf escapes stored text, and CSS preserves line breaks and wraps long content. Domain validation and optimistic concurrency remain in the application service.

## Small steps

1. Trace the item GET route, template, and POST/redirect/GET save flow.
2. Display saved content in full-height sections with readable line length and section links.
3. Offer editing through `?edit=true`, using the existing form and larger text areas.
4. Reopen editing automatically when a rejected save returns a preserved draft.
5. Verify long content, escaped HTML, saving, conflict recovery, and desktop/mobile layout.

## Review notes

The preview displays stored plain text, including line breaks; it does not interpret HTML or Markdown. Reading never submits changes. A successful save returns to the preview, while a validation or version error shows the preserved form against the current item version. Review the conflict message before retrying a save.

The browser checks should confirm that content is neither clipped nor horizontally overflowing at narrow widths. Integration coverage checks that long text is present in full, HTML remains escaped, saved results appear in the preview, and rejected edits remain available in the form.
