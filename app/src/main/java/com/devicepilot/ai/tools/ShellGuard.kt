package com.devicepilot.ai.tools

/**
 * Hard blocklist for the generic `shell` tool and file tools. Confirmation dialogs
 * handle "risky"; this handles "never, regardless of what the model or user says".
 */
object ShellGuard {

    private val blockedPatterns = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*r[a-zA-Z]*\s+)?(/|/data|/system|/sdcard|/storage|\*|~)\s*$"""),
        Regex("""\brm\s+-[a-zA-Z]*r[a-zA-Z]*f?\s+/(\s|$)"""),
        Regex("""\bmkfs\b"""), Regex("""\bdd\s+.*of=/dev/"""),
        Regex("""\bfastboot\b"""), Regex("""\brecovery\b.*--wipe"""),
        Regex("""\bwipe\b"""), Regex("""factory[_\s-]?reset""", RegexOption.IGNORE_CASE),
        Regex("""\bMASTER_CLEAR\b"""), Regex("""\bFACTORY_RESET\b"""),
        Regex("""\bpm\s+uninstall\b.*\bcom\.android\.(systemui|settings|phone|launcher)"""),
        Regex("""\bpm\s+disable(-user)?\b.*\bcom\.android\.(systemui|settings|phone)"""),
        Regex("""\bpm\s+uninstall\b.*\bmoe\.shizuku\.privileged\.api\b"""),
        Regex("""\bpm\s+uninstall\b.*\bcom\.devicepilot\.ai\b"""),
        Regex("""\bsettings\s+put\s+global\s+(adb_enabled|development_settings_enabled)\s+0"""),
        Regex(""":\(\)\s*\{\s*:\|:&\s*\};:"""),   // fork bomb
        Regex("""\bchmod\s+(-R\s+)?0*[0-7]{3,4}\s+/(system|data)?\s*$"""),
        Regex("""\bsetenforce\s+0"""),
        Regex("""\bsu\b"""), Regex("""\breboot\s+(bootloader|recovery|fastboot)"""),
        Regex("""\bam\s+broadcast\b.*MASTER_CLEAR"""),
        Regex("""\bcmd\s+package\s+(uninstall|clear)\b.*\b(com\.android|android)\b"""),
        Regex("""\bdpm\s+(set-device-owner|remove-active-admin|wipe)""")
    )

    fun check(command: String): String? {
        val c = command.trim()
        if (c.isBlank()) return "Empty command."
        for (p in blockedPatterns) {
            if (p.containsMatchIn(c)) return "Blocked by safety policy: this command could brick the device, wipe data, or lock out Shizuku/DevicePilot."
        }
        return null
    }

    private val protectedPathPrefixes = listOf("/system", "/vendor", "/data/system", "/data/data/moe.shizuku", "/data/data/com.devicepilot.ai", "/dev", "/proc", "/sys")

    fun checkPath(path: String): String? {
        val p = path.trim()
        if (p.isBlank() || p == "/" ) return "Refusing to operate on root path."
        if (protectedPathPrefixes.any { p.startsWith(it) }) return "Path $p is protected by safety policy."
        if (p.contains("..")) return "Relative path segments are not allowed."
        return null
    }
}
