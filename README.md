# CommitWise for IntelliJ IDEA

Read the Git changes you're about to commit, let your own LLM write a well-formed commit message, then review the code and mark issues on exact lines. Keys stay in the IDE PasswordSafe; nothing is ever committed automatically.

> Ported 1:1 from the VSCode edition of CommitWise (originally CommitCraft).

## Highlights

| Capability | What it does |
|------------|--------------|
| Generate commit message | Reads the staged diff or the checkbox-selected files in IDEA's Commit dialog, then renders Conventional Commits / Angular / Gitmoji / plain-text via your own template. The existing draft is backed up before writing and can be restored. |
| Code review | Five dimensions (defect / security / performance / maintainability / test); issues are anchored to exact added lines with file-level fallback. |
| Editor markers | Review results show as squiggles on the exact line; `Alt+Enter` offers "Apply suggestion" and "Open report" Quick Fixes. |
| Apply suggestion | Replace the line / insert below / cancel, preview before applying, undoable. |
| Review report | Standalone report window + "Copy as Markdown"; tool window grouped by file with click-to-jump. |
| Model providers | OpenAI-compatible (DeepSeek / Qwen / Moonshot / GLM / self-hosted gateway), Anthropic, Azure OpenAI, local Ollama. |
| Model list | Auto-fetch `/models` (24h cache + refresh), merged with manual candidates, searchable picker. |
| Context control | Per-file and total char budgets, glob exclusion, secret redaction before sending; oversized files dropped largest-first. |
| History | Last 10 commit messages (ring buffer) with restore / copy / delete. |
| Status bar | Shows current model and last review issue count; click to open the report or switch model. |
| Settings | Six sections: model service / request params / commit / code review / context & privacy / custom templates. |

## Install

**From JetBrains Marketplace** (once published): `Settings → Plugins → Marketplace → search "CommitWise" → Install`.

**From disk** (local build or GitHub release):

```
Settings → Plugins → ⚙ → Install Plugin from Disk… → select commitwise-idea-<version>.zip
```

Requires IntelliJ IDEA 2024.3+ and the bundled Git integration (Git4Idea, enabled by default).

## Usage

1. **Configure the model**: `Tools → CommitWise → 快速配置模型`, pick an endpoint (DeepSeek / OpenAI / Qwen / Moonshot / GLM / Ollama / Anthropic / Azure), confirm URL and model, enter the API key, test the connection.
2. **Generate a commit message**: open the Commit dialog (`Ctrl+K`), check the files to commit, click the CommitWise button in the commit-message toolbar. Or use `Tools → CommitWise → 生成提交信息`.
3. **Review the staged code**: `Tools → CommitWise → 审查暂存区代码`. Results appear in the CommitWise tool window on the right and as editor squiggles.
4. **Apply a suggestion**: pick an issue in the tool window and click "Apply", or press `Alt+Enter` on the marked line.

Custom keybindings are available under `Settings → Keymap → CommitWise`.

## Build

The project ships with the Gradle Wrapper, so no local Gradle install is needed — only JDK 21+ (the JBR bundled with IntelliJ IDEA works).

```bash
# Compile
./gradlew compileKotlin

# Unit tests (206 cases across domain + infra + app)
./gradlew test

# Structure check + package (output: build/distributions/commitwise-idea-<version>.zip)
./gradlew verifyPluginStructure buildPlugin

# Compatibility verification (downloads plugin verifier + several IDE distros)
./gradlew verifyPlugin

# Launch a sandbox IDE with the plugin installed
./gradlew runIde
```

Version, since/until build, and platform SDK version live in `gradle.properties`.

### Local build with a slow network

By default the build fetches the IntelliJ Platform distributable (~1 GB) from JetBrains servers. If your network is restricted, set `useLocalIde=true` and point `localIdePath` at an installed IntelliJ IDEA in `gradle.properties` to use it as the compile SDK. Keep `useLocalIde=false` when building a release for the Marketplace.

### Test coverage

`src/test/kotlin` covers every GUI-free code path; test names describe behavior:

| Test class | Cases | Coverage |
|------------|-------|----------|
| `DiffParserTest` | 11 | multi-file split, hunk line increment, add/delete/rename/binary, quoted paths, lossless patch round-trip |
| `GlobTest` | 7 | `**` / `*` / `?` semantics, anchored vs non-anchored, path normalization |
| `RedactTest` | 10 | ten redaction rules, key names preserved, ordinary code untouched |
| `PrepareDiffTest` | 11 | exclusion rules, binary/empty-hunk skip, per-file truncation, total-budget drop, redaction timing |
| `MessageValidatorTest` | 22 | fence/chitchat/quote cleanup, type validation, length and period warnings, gitmoji prefix, correction prompt |
| `ReviewParserTest` | 12 | fence/prose/trailing-comma/top-array tolerance, alias normalization, malformed entry count |
| `IssueMapperTest` | 12 | line-level anchoring and fallback, renamed old path, suffix match, severity filter/sort/limit |
| `RenderTemplateTest` / `TemplateRegistryTest` | 11 + 7 | variable substitution and missing vars, template validation, fallback rules |
| `ReviewMarkdownTest` | 7 | report header stats, per-file grouping, suggestion code blocks, truncation notice |
| `SseParserTest` / `NdjsonParserTest` | 13 + 4 | cross-chunk event split, `\n\n` and `\r\n\r\n` boundaries, multi-line data, comment frames |
| `DiffCollectorTest` | 11 | real temp repo: unborn HEAD, staged-only collection, delete/rename/binary, log order |
| `ConfigValidatorTest` / `ProviderPresetsTest` | 26 + 13 | URL validation, model backfill, request-param bounds, key warnings, endpoint matching |
| `PromptBuilderTest` | 16 | eleven template variables, truncation/exclusion notes, message assembly |
| `ModelCatalogTest` | 13 | chat vs non-chat detection, preference ordering, dedup, default model pick |

`DiffCollectorTest` actually runs `git init` in a temp directory; it self-skips when git is unavailable (JUnit `Assumptions`).

## Releasing

Two GitHub Actions workflows drive CI and releases:

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `.github/workflows/build.yml` | push/PR to `main` | `buildPlugin` + unit tests; uploads the `.zip` artifact, and on `main` publishes a **rolling pre-release** to GitHub Releases (tag `latest`) |
| `.github/workflows/release.yml` | tag `v*` or manual dispatch | `verifyPlugin` + `publishPlugin` to JetBrains Marketplace + GitHub Release |

### Rolling pre-release (`latest`)

Every successful build on `main` updates a single pre-release named **Latest build (main)**, tagged `latest`, with the freshly built `.zip` attached. No tagging is needed — grab the newest build from the [Releases page](../../releases) any time.

The rolling release is marked `prerelease` with `make_latest: false`, so it never takes the "Latest" badge away from versioned releases (`v*`). Pull requests only build and upload a short-lived artifact; they never publish.

### Required GitHub Secrets

Configure under **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Required | Description |
|--------|----------|-------------|
| `PUBLISH_TOKEN` | yes | JetBrains Marketplace Personal Access Token — generate at <https://plugins.jetbrains.com/author/me/tokens>. |
| `CERTIFICATE_CHAIN` | yes | Base64-encoded PEM certificate chain — generate at Marketplace → your plugin → **Signing → Generate certificate chain**. |
| `PRIVATE_KEY` | yes | Base64-encoded PEM private key matching the certificate chain (returned together with the chain). |
| `PRIVATE_KEY_PASSWORD` | optional | Password for the private key, if you set one when generating the certificate. |

> **Why signing is required.** Since the 2021.2 release cycle, JetBrains Marketplace rejects unsigned plugin uploads. The `signPlugin` task runs automatically before `publishPlugin` whenever `CERTIFICATE_CHAIN` + `PRIVATE_KEY` are present.

### First-time publishing checklist

1. **Upload the first version manually.** The Marketplace requires the very first version of a new plugin to be uploaded via the web UI — `publishPlugin` only works for plugins that already exist in the Marketplace. Build locally with `./gradlew buildPlugin`, sign it (`./gradlew signPlugin` if you have the env vars set, or use the Marketplace's web upload which signs server-side), then submit at <https://plugins.jetbrains.com> → **Add new plugin**. Approval is reviewed by JetBrains.
2. **Generate the signing certificate** on the plugin's Marketplace page once it exists.
3. **Add the four secrets** listed above to the GitHub repository.
4. **Bump `pluginVersion`** in `gradle.properties` (or let the release workflow do it from the tag name), commit, and tag `git tag v0.5.1 && git push --tags`. The release workflow will build, verify, sign, publish, and create a GitHub Release with the `.zip` attached.

### Manual dispatch

`Actions → Release → Run workflow` lets you publish any SemVer string without creating a tag, with an option to skip the Plugin Verifier for a faster (but less safe) run.

## Code layout

Mirrors the VSCode edition layer by layer; the domain layer has zero IDE dependencies and is fully unit-testable:

```
src/main/kotlin/com/commitwise/idea/
├── domain/                 # pure Kotlin, no IDEA deps
│   ├── diff/               # parseDiff / renderFilePatch / glob / redact / budget
│   ├── commit/             # output cleanup, format validation, correction prompt
│   ├── prompt/             # template rendering, six built-in templates, registry
│   └── review/             # JSON-tolerant parse, diff alignment, Markdown report
├── infra/                  # talks to the outside world
│   ├── ai/                 # four adapters (SSE / NDJSON incremental), retry, catalog, cache
│   ├── git/                # git CLI wrapper, change collection, repo location
│   ├── config/             # provider presets, config validation
│   └── log/
├── app/                    # use-case orchestration: generate commit, review staged
├── ui/                     # settings, tool window, report, dialogs, status bar, apply suggestion
├── inspection/             # squiggles + Quick Fix
├── actions/                # actions (Tools menu + commit toolbar + editor popup)
└── services/               # persistence: settings, history, review store, secrets
```

## Differences from the VSCode edition

Feature-equivalent, different medium:

- Settings, history, review results are stored in IDE XML state files (`commitwise.xml` + project-level `PropertiesComponent`) instead of `settings.json` / `globalState`.
- Keys live in PasswordSafe (the system credential store), matching VSCode `SecretStorage` behavior.
- The review report renders as Swing HTML instead of a Webview; button layout is adjusted accordingly.
- Quick Setup merges the VSCode wizard's four sequential inputs into a single form.

## Known limitations

- Writing the generated message into the commit input box depends on the Commit tool window being available; if it isn't open, the message is copied to the clipboard with a prompt to open the window.
- Azure OpenAI does not expose a model-list endpoint; the deployment name must be entered manually.
- Editor markers are scoped to the current review result; clearing the result removes the squiggles.

## License

MIT
