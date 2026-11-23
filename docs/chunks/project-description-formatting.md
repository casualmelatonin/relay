# Preserve project description formatting

## Outcome and definition of done

Project cards and project plans preserve newlines, blank paragraphs, and indentation from the saved description. Long words wrap inside narrow screens. The browser receives the updated stylesheet without needing a hard refresh.

## Why this matters and what to learn

The service already stores internal newlines, and Thymeleaf emits them safely. Browsers collapse whitespace in ordinary paragraphs, which makes a structured project description look like one unformatted block. Learn how CSS `white-space: pre-wrap` changes presentation without changing stored content or interpreting it as HTML.

## Small steps

1. Trace the project card and project-plan description templates.
2. Add the same whitespace and wrapping class to both views.
3. Let the flex children shrink so long text wraps on mobile.
4. Refresh stylesheet versions and verify multiline content in desktop and mobile views.

## Review notes

Descriptions remain plain text, escaped with `th:text`. This chunk preserves text formatting without adding rich-text or Markdown interpretation. No persistence or application-service changes are needed. Verify with `./mvnw verify` and browser layout checks.
