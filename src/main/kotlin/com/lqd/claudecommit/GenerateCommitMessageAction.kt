package com.lqd.claudecommit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import java.io.StringWriter
import java.nio.file.Paths

private const val CLAUDE_MODEL = "claude-sonnet-4-6"
private const val CLAUDE_TIMEOUT_MS = 180_000L
private const val RECENT_COMMITS_COUNT = 30

private val PROMPT_BASE = """
	Generate a Conventional Commits message in English for the git diff provided on stdin.

	Format: <type>(<scope>): <subject>

	Rules:
	- type: exactly one of feat, fix, refactor, chore, docs, style, test, perf, ci, build.
	- subject: under 70 chars, imperative mood, no trailing period, English.
	- scope is REQUIRED. Never omit the parentheses. Never use placeholders like "scope",
	  "general", "misc" — pick a real component name.

	Scope inference, in priority order:
	1. If the change touches ONLY composer.json and/or composer.lock → output exactly:
	   chore(deps): update <packages> dependency
	   (replace <packages> with the actual package names from the diff)
	2. Otherwise, reuse a scope already used in this project's recent commit history (provided
	   below) when the change continues or relates to that area — keep the project convention.
	3. Otherwise, infer a short component name from the changed files (a module/package name,
	   a top-level directory, or the dominant filename without extension).

	Body:
	- If the change is non-trivial, add a blank line and a short bullet-list explaining WHY (not WHAT).

	Output ONLY the commit message. No preamble, no code fences, no commentary, no closing remarks.
""".trimIndent()

class GenerateCommitMessageAction : AnAction(
	"Generate Commit Message with AI",
	"Generate a Conventional Commits message from selected changes via local CLI AI",
	AllIcons.Actions.IntentionBulb,
) {
	private val log = logger<GenerateCommitMessageAction>()

	override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

	override fun update(e: AnActionEvent) {
		val workflowUi = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
		val messageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
		e.presentation.isEnabledAndVisible =
			e.project != null && workflowUi != null && messageControl != null
	}

	override fun actionPerformed(e: AnActionEvent) {
		val project = e.project ?: return
		val workflowUi = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return
		val messageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return

		val included = workflowUi.getIncludedChanges()
		if (included.isEmpty()) {
			Messages.showInfoMessage(
				project,
				"No changes are selected for commit.",
				"CLI AI Commit Generator",
			)
			return
		}

		FileDocumentManager.getInstance().saveAllDocuments()

		ProgressManager.getInstance().run(
			object : Task.Backgroundable(project, "Generating commit message via Claude…", true) {
				override fun run(indicator: ProgressIndicator) {
					indicator.isIndeterminate = true
					indicator.text = "Building diff…"

					val diff = try {
						ReadAction.compute<String, Exception> { buildDiff(project, included) }
					} catch (ex: Exception) {
						log.warn("Failed to build patch", ex)
						notify(project, "Failed to build patch: ${ex.message}", isError = true)
						return
					}

					if (diff.isBlank()) {
						notify(
							project,
							"Selected changes produced an empty textual diff.",
							isError = false,
						)
						return
					}

					val recentCommits = try {
						getRecentCommitSubjects(project)
					} catch (ex: Exception) {
						log.warn("Failed to read recent commits — continuing without scope context", ex)
						""
					}
					val effectivePrompt = if (recentCommits.isNotBlank()) {
						"$PROMPT_BASE\n\nRecent commit subjects in this project (newest first), use to keep scope conventions consistent:\n$recentCommits"
					} else {
						PROMPT_BASE
					}

					indicator.text = "Streaming from claude --model sonnet…"
					try {
						streamClaude(project.basePath, diff, effectivePrompt) { partial ->
							ApplicationManager.getApplication().invokeLater {
								messageControl.setCommitMessage(partial)
							}
						}
					} catch (ex: Exception) {
						log.warn("Claude invocation failed", ex)
						notify(project, "Claude failed: ${ex.message}", isError = true)
					}
				}
			},
		)
	}

	private fun buildDiff(project: Project, changes: Collection<Change>): String {
		val basePath = Paths.get(project.basePath ?: System.getProperty("user.dir"))
		val patches = IdeaTextPatchBuilder.buildPatch(
			project,
			changes,
			basePath,
			false,
			true,
		)
		val writer = StringWriter()
		UnifiedDiffWriter.write(project, patches, writer, "\n", null)
		return writer.toString()
	}

	private fun getRecentCommitSubjects(project: Project): String {
		val basePath = project.basePath ?: return ""
		val cmd = GeneralCommandLine(
			"git",
			"log",
			"--no-merges",
			"--pretty=format:%s",
			"-$RECENT_COMMITS_COUNT",
			"HEAD",
		).withWorkDirectory(basePath).withCharset(Charsets.UTF_8)
		val handler = OSProcessHandler(cmd)
		val out = StringBuilder()
		handler.addProcessListener(
			object : ProcessAdapter() {
				override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
					if (outputType == ProcessOutputTypes.STDOUT) {
						out.append(event.text)
					}
				}
			},
		)
		handler.startNotify()
		if (!handler.waitFor(5_000)) {
			handler.destroyProcess()
			return ""
		}
		if ((handler.exitCode ?: -1) != 0) return ""
		return out.toString().trim()
	}

	private fun streamClaude(basePath: String?, diff: String, prompt: String, onPartial: (String) -> Unit) {
		val claudeArgs = listOf(
			"--effort", "low",
			"--model", CLAUDE_MODEL,
			"-p", prompt,
			"--output-format", "stream-json",
			"--include-partial-messages",
			"--verbose",
		)
		val cmd = buildClaudeCommandLine(basePath, claudeArgs)

		val handler = OSProcessHandler(cmd)
		val accumulated = StringBuilder()
		val lineBuffer = StringBuilder()
		val stderr = StringBuilder()

		handler.addProcessListener(
			object : ProcessAdapter() {
				override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
					if (outputType == ProcessOutputTypes.STDERR) {
						stderr.append(event.text)
						return
					}
					if (outputType != ProcessOutputTypes.STDOUT) return
					synchronized(lineBuffer) {
						lineBuffer.append(event.text)
						while (true) {
							val nl = lineBuffer.indexOf('\n')
							if (nl < 0) break
							val line = lineBuffer.substring(0, nl)
							lineBuffer.delete(0, nl + 1)
							processStreamLine(line, accumulated, onPartial)
						}
					}
				}
			},
		)

		handler.startNotify()
		handler.processInput.use { it.write(diff.toByteArray(Charsets.UTF_8)) }

		if (!handler.waitFor(CLAUDE_TIMEOUT_MS)) {
			handler.destroyProcess()
			throw RuntimeException("claude timed out after ${CLAUDE_TIMEOUT_MS / 1000}s")
		}

		val exit = handler.exitCode ?: -1
		if (exit != 0) {
			throw RuntimeException("claude exited with code $exit: ${stderr.toString().take(500)}")
		}

		// Final flush — emit accumulated text once more in case the last line ended without newline.
		val tail = synchronized(lineBuffer) {
			val remaining = lineBuffer.toString()
			lineBuffer.setLength(0)
			remaining
		}
		if (tail.isNotBlank()) {
			processStreamLine(tail, accumulated, onPartial)
		}
		// Trim final whitespace and update once more in case the model produced trailing newlines.
		val finalText = accumulated.toString().trim()
		if (finalText.isNotEmpty()) {
			onPartial(finalText)
		}
	}

	// Windows IDE on a \\wsl(.localhost|$)\<distro>\... project: invoke claude via wsl.exe so it runs WSL-side.
	private fun buildClaudeCommandLine(basePath: String?, claudeArgs: List<String>): GeneralCommandLine {
		val distro = detectWslDistroFromUncPath(basePath)
		val full = if (distro != null) {
			listOf("wsl.exe", "-d", distro, "--", "claude") + claudeArgs
		} else {
			listOf("claude") + claudeArgs
		}
		return GeneralCommandLine(full).withCharset(Charsets.UTF_8)
	}

	private fun detectWslDistroFromUncPath(basePath: String?): String? {
		if (basePath == null) return null
		if (!System.getProperty("os.name").orEmpty().lowercase().contains("windows")) return null
		val normalized = basePath.replace('\\', '/')
		val match = Regex("""^//wsl(?:\$|\.localhost)/([^/]+)(?:/.*)?$""", RegexOption.IGNORE_CASE)
			.matchEntire(normalized) ?: return null
		return match.groupValues[1]
	}

	private fun processStreamLine(
		line: String,
		accumulated: StringBuilder,
		onPartial: (String) -> Unit,
	) {
		if (line.isBlank()) return
		val root = try {
			JsonParser.parseString(line) as? JsonObject ?: return
		} catch (_: Exception) {
			return
		}
		// Only handle stream_event → content_block_delta → text_delta. Ignore thinking_delta
		// and snapshot "assistant" messages.
		if (root.get("type")?.asString != "stream_event") return
		val ev = root.getAsJsonObject("event") ?: return
		if (ev.get("type")?.asString != "content_block_delta") return
		val delta = ev.getAsJsonObject("delta") ?: return
		if (delta.get("type")?.asString != "text_delta") return
		val text = delta.get("text")?.asString ?: return
		accumulated.append(text)
		onPartial(accumulated.toString())
	}

	private fun notify(project: Project, message: String, isError: Boolean) {
		ApplicationManager.getApplication().invokeLater {
			if (isError) {
				Messages.showErrorDialog(project, message, "CLI AI Commit Generator")
			} else {
				Messages.showInfoMessage(project, message, "CLI AI Commit Generator")
			}
		}
	}
}
