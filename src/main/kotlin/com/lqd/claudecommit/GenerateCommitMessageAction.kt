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

private const val CLAUDE_MODEL = "claude-haiku-4-5-20251001"
private const val CLAUDE_TIMEOUT_MS = 180_000L

private val PROMPT = """
	Generate a Conventional Commits message in English for the git diff provided on stdin.

	Format:
	- First line: <type>(<scope>): <subject> — under 70 chars, imperative mood, no trailing period.
	- type: one of feat, fix, refactor, perf, docs, test, chore, style, build, ci.
	- scope: a short component name inferred from the changed files. Omit "(scope)" entirely if it would be vague.
	- If the change is non-trivial, add a blank line and a short bullet-list body explaining WHY (not WHAT).
	- Output ONLY the commit message. No preamble, no code fences, no commentary, no closing remarks.
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

					indicator.text = "Streaming from claude --model haiku…"
					try {
						streamClaude(diff) { partial ->
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

	private fun streamClaude(diff: String, onPartial: (String) -> Unit) {
		val cmd = GeneralCommandLine(
			"claude",
			"--effort", "low",
			"--model", CLAUDE_MODEL,
			"-p", PROMPT,
			"--output-format", "stream-json",
			"--include-partial-messages",
			"--verbose",
		).withCharset(Charsets.UTF_8)

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
