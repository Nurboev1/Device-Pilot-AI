package com.devicepilot.ai.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.devicepilot.ai.shizuku.ShizukuShell
import com.devicepilot.ai.shizuku.ShizukuShell.q
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** App lifecycle & package management tools. */
object AppTools {

    private const val CAT = "apps"

    // label(lowercase) -> package, refreshed lazily
    @Volatile private var labelCache: Map<String, String> = emptyMap()
    @Volatile private var labelCacheAt = 0L

    suspend fun labelIndex(context: Context, force: Boolean = false): Map<String, String> = withContext(Dispatchers.IO) {
        if (!force && labelCache.isNotEmpty() && System.currentTimeMillis() - labelCacheAt < 120_000) return@withContext labelCache
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val map = HashMap<String, String>()
        try {
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val label = ri.loadLabel(pm).toString().lowercase().trim()
                if (label.isNotBlank()) map.putIfAbsent(label, ri.activityInfo.packageName)
            }
        } catch (_: Throwable) { }
        labelCache = map; labelCacheAt = System.currentTimeMillis()
        map
    }

    /** Resolve a user-facing name or package into a package name. */
    suspend fun resolvePackage(context: Context, query: String): String? {
        val qn = query.trim().lowercase()
        if (qn.contains('.') && !qn.contains(' ')) return qn // already a package
        val idx = labelIndex(context)
        idx[qn]?.let { return it }
        idx.entries.firstOrNull { it.key.startsWith(qn) }?.let { return it.value }
        idx.entries.firstOrNull { it.key.contains(qn) }?.let { return it.value }
        idx.entries.firstOrNull { it.value.substringAfterLast('.').contains(qn) }?.let { return it.value }
        // Last resort: pm list filter
        val r = ShizukuShell.run("pm list packages | grep -i ${q(qn)} | head -1")
        return r.stdout.trim().removePrefix("package:").ifBlank { null }
    }

    fun all(context: Context): List<Tool> = listOf(

        SimpleTool(
            name = "open_app",
            description = "Launch an app by its name (e.g. \"Telegram\", \"Settings\", \"Camera\") or package name.",
            params = listOf(Param("app", "string", "App name or package")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Open ${a.string("app")}" }
        ) { a ->
            val query = a.requireString("app")
            val pkg = resolvePackage(context, query) ?: return@SimpleTool ToolResult.fail("No installed app matches \"$query\". Use list_apps to search.")
            val r = ShizukuShell.run("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 && echo launched || (am start -n \$(cmd package resolve-activity --brief $pkg | tail -1) >/dev/null 2>&1 && echo launched)")
            delay(1500)
            if (r.stdout.contains("launched")) ToolResult.ok("Opened $pkg. Call get_screen to see it.", "monkey -p $pkg") else ToolResult.fail("Failed to launch $pkg: ${r.text}", r.command)
        },

        SimpleTool(
            name = "list_apps",
            description = "List installed apps (name and package). Optionally filter by a search string.",
            params = listOf(
                Param("query", "string", "Substring to match against app name or package", required = false),
                Param("include_system", "boolean", "Include system apps (default false)", required = false)
            ),
            category = CAT,
            summary = { a -> "List apps${a.string("query")?.let { " matching \"$it\"" } ?: ""}" }
        ) { a ->
            val query = a.string("query")?.lowercase()?.trim()
            val includeSystem = a.bool("include_system") == true
            val idx = labelIndex(context, force = true)
            val userPkgs = ShizukuShell.run("pm list packages -3").stdout.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotBlank() }.toSet()
            val entries = idx.entries
                .filter { includeSystem || it.value in userPkgs }
                .filter { query.isNullOrBlank() || it.key.contains(query) || it.value.contains(query) }
                .sortedBy { it.key }
            if (entries.isEmpty()) return@SimpleTool ToolResult.ok("No apps match \"$query\".")
            val body = entries.take(40).joinToString("\n") { "- ${it.key} (${it.value})" }
            ToolResult.ok("${entries.size} apps:\n$body" + if (entries.size > 40) "\n... ${entries.size - 40} more; refine the query." else "", "pm list packages")
        },

        SimpleTool(
            name = "app_info",
            description = "Get details about an app: version, install time, permissions granted, enabled state.",
            params = listOf(Param("app", "string", "App name or package")),
            category = CAT,
            summary = { a -> "Info: ${a.string("app")}" }
        ) { a ->
            val pkg = resolvePackage(context, a.requireString("app")) ?: return@SimpleTool ToolResult.fail("App not found.")
            val r = ShizukuShell.run("dumpsys package $pkg | grep -E 'versionName|firstInstallTime|lastUpdateTime|enabled=|granted=true' | head -40")
            if (r.stdout.isBlank()) return@SimpleTool ToolResult.fail("No info for $pkg.", r.command)
            val perms = Regex("""(android\.permission\.[A-Z_]+): granted=true""").findAll(r.stdout).map { it.groupValues[1].substringAfterLast('.') }.toList()
            val meta = r.stdout.lines().filter { !it.contains("granted=") }.joinToString("\n") { it.trim() }
            ToolResult.ok("package=$pkg\n$meta\ngranted_permissions=${perms.joinToString(",")}", r.command)
        },

        SimpleTool(
            name = "force_stop_app",
            description = "Force-stop a running app.",
            params = listOf(Param("app", "string", "App name or package")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Force stop ${a.string("app")}" }
        ) { a ->
            val pkg = resolvePackage(context, a.requireString("app")) ?: return@SimpleTool ToolResult.fail("App not found.")
            val r = ShizukuShell.run("am force-stop $pkg")
            if (r.ok) ToolResult.ok("Force-stopped $pkg.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "uninstall_app",
            description = "Uninstall an app. Destructive — the user will be asked to confirm.",
            params = listOf(Param("app", "string", "App name or package")),
            risk = Risk.CONFIRM, category = CAT,
            summary = { a -> "Uninstall ${a.string("app")}" }
        ) { a ->
            val pkg = resolvePackage(context, a.requireString("app")) ?: return@SimpleTool ToolResult.fail("App not found.")
            if (pkg == context.packageName) return@SimpleTool ToolResult.fail("Refusing to uninstall DevicePilot itself.")
            val r = ShizukuShell.run("pm uninstall --user 0 $pkg")
            labelCacheAt = 0
            if (r.stdout.contains("Success")) ToolResult.ok("Uninstalled $pkg.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "clear_app_data",
            description = "Erase all data of an app (like a fresh install). Destructive — requires confirmation.",
            params = listOf(Param("app", "string", "App name or package")),
            risk = Risk.CONFIRM, category = CAT,
            summary = { a -> "Clear data of ${a.string("app")}" }
        ) { a ->
            val pkg = resolvePackage(context, a.requireString("app")) ?: return@SimpleTool ToolResult.fail("App not found.")
            val r = ShizukuShell.run("pm clear $pkg")
            if (r.stdout.contains("Success")) ToolResult.ok("Cleared data for $pkg.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "clear_app_cache",
            description = "Clear only the cache of an app (keeps login/data).",
            params = listOf(Param("app", "string", "App name or package")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Clear cache of ${a.string("app")}" }
        ) { a ->
            val pkg = resolvePackage(context, a.requireString("app")) ?: return@SimpleTool ToolResult.fail("App not found.")
            val r = ShizukuShell.run("pm clear --cache-only $pkg 2>/dev/null || rm -rf /data/data/$pkg/cache/* 2>/dev/null && echo Success")
            if (r.stdout.contains("Success")) ToolResult.ok("Cleared cache for $pkg.", r.command) else ToolResult.fail(r.text.ifBlank { "Cache clear not supported on this Android version." }, r.command)
        },

        SimpleTool(
            name = "set_app_enabled",
            description = "Disable (freeze) or re-enable an app without uninstalling it.",
            params = listOf(Param("app", "string", "App name or package"), Param("enabled", "boolean", "true to enable, false to disable")),
            risk = Risk.CONFIRM, category = CAT,
            summary = { a -> "${if (a.bool("enabled") == false) "Disable" else "Enable"} ${a.string("app")}" }
        ) { a ->
            val pkg = resolvePackage(context, a.requireString("app")) ?: return@SimpleTool ToolResult.fail("App not found.")
            val en = a.requireBool("enabled")
            if (!en && pkg == context.packageName) return@SimpleTool ToolResult.fail("Refusing to disable DevicePilot itself.")
            val r = ShizukuShell.run(if (en) "pm enable $pkg" else "pm disable-user --user 0 $pkg")
            if (r.ok) ToolResult.ok("$pkg is now ${if (en) "enabled" else "disabled"}.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "install_apk",
            description = "Install an APK from a file path on the device (e.g. /sdcard/Download/app.apk). Requires confirmation.",
            params = listOf(Param("path", "string", "Absolute path to the .apk")),
            risk = Risk.CONFIRM, category = CAT,
            summary = { a -> "Install APK ${a.string("path")}" }
        ) { a ->
            val path = a.requireString("path")
            val r = ShizukuShell.run("pm install -r -g ${q(path)}", timeoutMs = 120_000)
            labelCacheAt = 0
            if (r.stdout.contains("Success")) ToolResult.ok("Installed $path.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "set_permission",
            description = "Grant or revoke a runtime permission for an app (e.g. CAMERA, RECORD_AUDIO, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS, READ_CONTACTS).",
            params = listOf(
                Param("app", "string", "App name or package"),
                Param("permission", "string", "Permission short name (CAMERA) or full name (android.permission.CAMERA)"),
                Param("grant", "boolean", "true to grant, false to revoke")
            ),
            risk = Risk.CONFIRM, category = CAT,
            summary = { a -> "${if (a.bool("grant") == false) "Revoke" else "Grant"} ${a.string("permission")} for ${a.string("app")}" }
        ) { a ->
            val pkg = resolvePackage(context, a.requireString("app")) ?: return@SimpleTool ToolResult.fail("App not found.")
            var perm = a.requireString("permission").uppercase()
            if (!perm.contains('.')) perm = "android.permission.$perm"
            val r = ShizukuShell.run("pm ${if (a.requireBool("grant")) "grant" else "revoke"} $pkg $perm")
            if (r.ok && r.stderr.isBlank()) ToolResult.ok("Done: $perm for $pkg.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "get_app_permissions",
            description = "List runtime permissions requested by an app and whether each is granted.",
            params = listOf(Param("app", "string", "App name or package")),
            category = CAT,
            summary = { a -> "Permissions of ${a.string("app")}" }
        ) { a ->
            val pkg = resolvePackage(context, a.requireString("app")) ?: return@SimpleTool ToolResult.fail("App not found.")
            val r = ShizukuShell.run("dumpsys package $pkg | grep -E 'android.permission.[A-Z_]+: granted=' | sort -u")
            val lines = r.stdout.lines().mapNotNull { l ->
                Regex("""android\.permission\.([A-Z_]+): granted=(\w+)""").find(l)?.let { "${it.groupValues[1]}=${it.groupValues[2]}" }
            }.distinct()
            if (lines.isEmpty()) ToolResult.ok("No runtime permissions recorded for $pkg.", r.command)
            else ToolResult.ok(lines.joinToString("\n"), r.command)
        },

        SimpleTool(
            name = "set_battery_optimization",
            description = "Whitelist an app from battery optimization (unrestricted background) or remove it from the whitelist.",
            params = listOf(Param("app", "string", "App name or package"), Param("unrestricted", "boolean", "true = never optimize")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Battery: ${a.string("app")} unrestricted=${a.bool("unrestricted")}" }
        ) { a ->
            val pkg = resolvePackage(context, a.requireString("app")) ?: return@SimpleTool ToolResult.fail("App not found.")
            val r = ShizukuShell.run("dumpsys deviceidle whitelist ${if (a.requireBool("unrestricted")) "+" else "-"}$pkg")
            if (r.ok) ToolResult.ok(r.text, r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "get_running_apps",
            description = "List apps currently running in the foreground/recent tasks.",
            category = CAT,
            summary = { "Running apps" }
        ) {
            val r = ShizukuShell.run("dumpsys activity recents 2>/dev/null | grep -oE 'A=[0-9]+:[a-zA-Z0-9_.]+' | cut -d: -f2 | head -15")
            val list = r.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (list.isEmpty()) {
                val r2 = ShizukuShell.run("dumpsys activity activities | grep -oE 'mResumedActivity.*' | head -3")
                return@SimpleTool ToolResult.ok(r2.stdout.ifBlank { "Could not determine running apps." }, r2.command)
            }
            ToolResult.ok("Recent apps (most recent first):\n" + list.joinToString("\n") { "- $it" }, r.command)
        },

        SimpleTool(
            name = "set_default_launcher",
            description = "Open the system default-apps settings so the user can choose defaults (browser, launcher, SMS...).",
            params = listOf(Param("role", "string", "Which default to change", choices = listOf("home", "browser", "sms", "dialer", "assistant", "all"), required = false)),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Open default apps (${a.string("role") ?: "all"})" }
        ) { a ->
            val r = ShizukuShell.run("am start -a android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
            if (r.ok) ToolResult.ok("Opened default apps settings. Call get_screen to continue.", r.command) else ToolResult.fail(r.text, r.command)
        }
    )

    fun isSystemApp(pm: PackageManager, pkg: String): Boolean = try {
        (pm.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (_: Throwable) { false }
}
