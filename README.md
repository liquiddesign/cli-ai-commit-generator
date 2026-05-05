# CLI AI Commit Generator

JetBrains IDE plugin (PhpStorm 2026.1+, IntelliJ IDEA, etc.) that generates a [Conventional Commits](https://www.conventionalcommits.org/) message from the changes you have selected in the commit dialog. The diff is piped into a local CLI AI assistant and the response is streamed back into the commit message field.

The action shows up next to the **Amend** toggle above the commit message:

> 💡 *Generate Commit Message with AI*

## How it works

1. You stage your changes in the commit dialog (file checkboxes; per-hunk include/exclude is honored).
2. You click the lightbulb action.
3. The plugin builds a unified diff of exactly what would be committed (`IdeaTextPatchBuilder` + `UnifiedDiffWriter`).
4. The diff is piped on stdin to:
   ```
   claude --effort low --model claude-sonnet-4-6 -p "<prompt>" \
          --output-format stream-json --include-partial-messages --verbose
   ```
5. Streamed `text_delta` events are parsed and the commit message field is updated as tokens arrive.

The prompt asks for a Conventional Commits message with optional bullet body explaining *why* (not *what*).

## Requirements

- **PhpStorm / IntelliJ IDEA 2026.1 or newer** (build 261+, no upper bound)
- The [Anthropic Claude CLI](https://docs.anthropic.com/en/docs/claude-code/quickstart) on `PATH` and authenticated (`claude` should produce output without `Please run /login`).

## Install

1. Download the latest ZIP from the **[Releases](https://github.com/liquiddesign/cli-ai-commit-generator/releases/latest)** page.
2. In your IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…** → pick the ZIP.
3. Restart the IDE.

## Build from source

```bash
git clone https://github.com/liquiddesign/cli-ai-commit-generator.git
cd claude-commit-plugin
./gradlew buildPlugin
# resulting ZIP: build/distributions/cli-ai-commit-generator-<version>.zip
```

Requires JDK 21+ and Gradle 9+ (the Gradle wrapper handles Gradle).

## Configuration

None — the model, effort level, and prompt are currently hard-coded. A settings UI is on the roadmap.

## License

[Apache-2.0](LICENSE)

## Disclaimer

Not affiliated with, endorsed by, or sponsored by Anthropic. "Claude" is a trademark of Anthropic — referenced here only descriptively (nominative use) to identify the CLI this plugin invokes.
