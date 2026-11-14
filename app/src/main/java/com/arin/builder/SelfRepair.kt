package com.arin.builder

import android.content.Context
import com.arin.ArinApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * When a build stops because of an error in Sanjoyingti's own code (not the app being built, not the person's
 * setup), Sanjoyingti repairs itself: the model looks the error up on the web, patches Sanjoyingti's source and a
 * new version is built. After that version is installed, the build that stopped carries on by itself.
 */
object SelfRepair {
    private fun prefs(c: Context) = c.getSharedPreferences("self_repair", Context.MODE_PRIVATE)

    /** Errors only the person can fix: sign-ins, keys, limits, no internet. Repairing the app won't help. */
    fun needsPerson(message: String): Boolean = listOf(
        "sign in", "signed in", "token", " key", "401", "403", "rate limit", "rate-limit", "quota", "allowance",
        "billing", "credit", "no internet", "network error", "unable to resolve host", "timeout", "timed out",
        "actions are disabled", "actions is disabled",
    ).any { message.contains(it, ignoreCase = true) }

    /** The same error with different ids or numbers is still the same error. */
    private fun signature(message: String) =
        message.replace(Regex("[0-9a-f]{7,40}"), "#").replace(Regex("\\d+"), "#").take(160)

    private fun versionCode(c: Context): Long = runCatching {
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    /**
     * Starts a repair of Sanjoyingti for the error in [result] (once per kind of error). Returns true when it started.
     */
    fun start(context: Context, failed: BuildRequest, result: BuildResult, selfRepo: String, selfBranch: String): Boolean {
        val app = context.applicationContext
        if (failed.selfUpdate || failed.githubToken.isBlank() || selfRepo.isBlank()) return false
        if (needsPerson(result.message)) return false
        val sig = signature(result.message)
        val p = prefs(app)
        if (p.getBoolean("tried_$sig", false)) return false
        p.edit()
            .putBoolean("tried_$sig", true)
            .putString("resume_project", BuildRunner.projectId)
            .putLong("resume_after_version", versionCode(app))
            .apply()
        val request = BuildRequest(
            description = buildString {
                append("Sanjoyingti stopped while building an app because of an error in Sanjoyingti's OWN code ")
                append("(not in the app being built, and not the person's setup):\n\n")
                append(result.message.take(1_000)).append("\n\n")
                if (result.log.isNotBlank()) append("Stack trace / log:\n").append(result.log.take(3_000)).append("\n\n")
                append("First look this error up on the web (<search>…</search>, <fetch>…</fetch>) to find what causes it ")
                append("and the right fix. Then patch Sanjoyingti's code so this situation is handled by itself next time ")
                append("(work around it, retry, or recover) instead of stopping the build. Keep everything else the same, ")
                append("and raise versionCode and versionName.")
            },
            appName = "Sanjoyingti",
            provider = failed.provider,
            model = failed.model,
            apiKey = failed.apiKey,
            effort = failed.effort,
            githubToken = failed.githubToken,
            allowOtherSites = true,
            selfUpdate = true,
            selfRepo = selfRepo,
            selfBranch = selfBranch,
        )
        ArinApp.scope.launch(Dispatchers.Main) {
            delay(2_000) // let the stopped build's service finish first
            if (BuildRunner.running) return@launch
            BuildRunner.newProject(app)
            BuildService.start(app, request)
        }
        return true
    }

    /**
     * The project whose build stopped for a repair, once the repaired version is installed (null otherwise).
     * Reading it clears it, so the build is continued only once.
     */
    fun takeResume(context: Context): String? {
        val p = prefs(context)
        val id = p.getString("resume_project", null) ?: return null
        if (versionCode(context) <= p.getLong("resume_after_version", Long.MAX_VALUE)) return null
        p.edit().remove("resume_project").remove("resume_after_version").apply()
        return id
    }
}
