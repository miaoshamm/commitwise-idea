# Keep a Changelog -> https://keepachangelog.com
# IntelliJ Platform Gradle Plugin (changelog) reads this file and injects the
# matching version block into plugin.xml <change-notes> at build time.

# Changelog

## [Unreleased]

## [0.5.0] - 2026-10-27

### Added
- One-click generation of Conventional Commits / Angular / Gitmoji / plain-text
  commit messages from the staged diff or Commit-dialog selected files.
- Five-dimension code review (defect / security / performance / maintainability /
  test) with issues aligned to exact added lines; file-level fallback when the
  line is not in the diff.
- Editor squiggles + `Alt+Enter` Quick Fix to apply a suggestion (replace /
  insert below / cancel) with undo support.
- Standalone review report window + "Copy as Markdown"; tool window grouped by
  file with click-to-jump.
- Four model providers: OpenAI-compatible (DeepSeek / Qwen / Moonshot / GLM /
  self-hosted gateway), Anthropic, Azure OpenAI, Ollama (local).
- Model list auto-fetch from `/models` with 24h cache + refresh, merged with
  manual candidates, searchable picker.
- Context budget control: per-file and total char limits, glob exclusion rules,
  secret-like redaction before sending; oversized files dropped largest-first.
- Commit message history (last 10, ring buffer) with restore / copy / delete.
- Status bar widget showing current model and last review issue count.
- Settings page with six sections: model service / request params / commit /
  code review / context & privacy / custom templates.
- Quick Setup wizard with eight built-in endpoints and connection test.
- Pre-flight config check: missing model or API key triggers a guided notice
  instead of a runtime error.
- Commit-dialog aware: reads checkbox-selected files (IDEA commit flow) rather
  than only `git diff --cached`.
- Flat toolbar buttons in the Commit dialog (not a popup menu).
- 206 unit tests covering diff parsing, redaction, glob, template rendering,
  commit validation, review parsing, issue mapping, SSE/NDJSON streaming, git
  collection, config validation, model catalog, prompt building.

### Fixed
- Gson lenient mode silently accepting prose-prefixed JSON as a string, which
  made the review-response tolerance path unreachable.
- URL scheme case-sensitivity rejecting `HTTPS://` inputs.
- `EndpointPresets` exact-match only, so a DeepSeek base URL without `/v1`
  fell back to an OpenAI model name and failed at runtime.
- Commit history never recording the branch name.
