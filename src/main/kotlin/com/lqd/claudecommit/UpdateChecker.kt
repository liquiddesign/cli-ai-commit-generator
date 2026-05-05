package com.lqd.claudecommit

import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PLUGIN_ID = "com.lqd.cli-ai-commit-generator"
private const val RELEASES_API =
	"https://api.github.com/repos/liquiddesign/cli-ai-commit-generator/releases/latest"
private const val RELEASES_PAGE =
	"https://github.com/liquiddesign/cli-ai-commit-generator/releases/latest"
private const val LAST_CHECK_KEY = "com.lqd.claudecommit.lastUpdateCheckMs"
private const val LAST_NOTIFIED_KEY = "com.lqd.claudecommit.lastNotifiedVersion"
private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
private const val NOTIFICATION_GROUP = "CliAiCommitGenerator.Updates"

class UpdateChecker : ProjectActivity {
	private val log = logger<UpdateChecker>()

	override suspend fun execute(project: Project) {
		val props = PropertiesComponent.getInstance()
		val now = System.currentTimeMillis()
		val lastCheck = props.getValue(LAST_CHECK_KEY)?.toLongOrNull() ?: 0L
		if (now - lastCheck < CHECK_INTERVAL_MS) return

		val current = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: return

		val latest = try {
			withContext(Dispatchers.IO) { fetchLatestTag() }
		} catch (ex: Exception) {
			log.info("Update check failed: ${ex.message}")
			return
		} ?: return

		props.setValue(LAST_CHECK_KEY, now.toString())

		if (compareVersions(latest, current) <= 0) return
		if (props.getValue(LAST_NOTIFIED_KEY) == latest) return
		props.setValue(LAST_NOTIFIED_KEY, latest)

		NotificationGroupManager.getInstance()
			.getNotificationGroup(NOTIFICATION_GROUP)
			.createNotification(
				"CLI AI Commit Generator: new version $latest available",
				"You have $current. Download the ZIP and install via " +
					"Settings → Plugins → ⚙ → Install Plugin from Disk…",
				NotificationType.INFORMATION,
			)
			.addAction(
				NotificationAction.createSimple("Open releases page") {
					BrowserUtil.browse(RELEASES_PAGE)
				},
			)
			.notify(project)
	}

	private fun fetchLatestTag(): String? {
		val response = HttpRequests.request(RELEASES_API)
			.accept("application/vnd.github+json")
			.connectTimeout(5_000)
			.readTimeout(5_000)
			.readString()
		val root = JsonParser.parseString(response).asJsonObject
		val tag = root.get("tag_name")?.asString ?: return null
		return tag.removePrefix("v")
	}

	private fun compareVersions(a: String, b: String): Int {
		val ap = a.split(Regex("[.\\-+]")).map { it.toIntOrNull() ?: 0 }
		val bp = b.split(Regex("[.\\-+]")).map { it.toIntOrNull() ?: 0 }
		val n = maxOf(ap.size, bp.size)
		for (i in 0 until n) {
			val ai = ap.getOrNull(i) ?: 0
			val bi = bp.getOrNull(i) ?: 0
			if (ai != bi) return ai.compareTo(bi)
		}
		return 0
	}
}
