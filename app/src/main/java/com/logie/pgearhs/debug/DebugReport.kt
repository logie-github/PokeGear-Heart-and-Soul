package com.logie.pgearhs.debug

data class DebugReport(
    val id: String,
    val issueUrl: String
)

object DebugReportFormatter {

    private const val ISSUE_BASE_URL = "https://github.com/logie-github/PokeGear-Heart-and-Soul/issues/new"
    private const val MAX_URL_LENGTH = 7_000

    fun create(
        timestampMillis: Long,
        appVersion: String,
        gitCommit: String,
        device: String,
        androidVersion: String,
        retroArchSyncStatus: String,
        logEntries: List<String>
    ): DebugReport {
        val id = timestampMillis.toString(36).uppercase()

        val header = buildString {
            appendLine("Report ID: $id")
            appendLine("App version: $appVersion (build $gitCommit)")
            appendLine("Device: $device")
            appendLine("Android version: $androidVersion")
            appendLine("RetroArch sync: $retroArchSyncStatus")
            appendLine()
            appendLine("```text")
        }
        val footer = "\n```"

        val title = java.net.URLEncoder.encode("Debug report $id", "UTF-8")
        val bodyBudget = MAX_URL_LENGTH - ISSUE_BASE_URL.length - title.length - 32

        var body = header + logEntries.joinToString("\n") + footer
        var encoded = java.net.URLEncoder.encode(body, "UTF-8")

        if (encoded.length > bodyBudget) {
            var trimmedEntries = logEntries
            while (trimmedEntries.isNotEmpty()) {
                trimmedEntries = trimmedEntries.drop(1)
                body = header + "[Earlier entries omitted]\n" + trimmedEntries.joinToString("\n") + footer
                encoded = java.net.URLEncoder.encode(body, "UTF-8")
                if (encoded.length <= bodyBudget) break
            }
        }

        val issueUrl = "$ISSUE_BASE_URL?title=$title&body=$encoded"
        return DebugReport(id, issueUrl)
    }
}
