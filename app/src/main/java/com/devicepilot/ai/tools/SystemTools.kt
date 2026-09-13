package com.devicepilot.ai.tools

import android.content.Context
import com.devicepilot.ai.shizuku.ShizukuShell
import com.devicepilot.ai.shizuku.ShizukuShell.q
import kotlinx.coroutines.delay

/** Device settings, connectivity, audio, display, power. */
object SystemTools {

    private const val CAT = "system"

    private suspend fun simple(cmd: String, okMsg: String): ToolResult {
        val r = ShizukuShell.run(cmd)
        return if (r.ok && !r.stderr.contains("Exception") && !r.stdout.contains("Exception")) ToolResult.ok(okMsg, cmd) else ToolResult.fail(r.text, cmd)
    }

    fun all(context: Context): List<Tool> = listOf(

        SimpleTool(
            name = "get_device_state",
            description = "Snapshot of device state: battery, Wi-Fi, Bluetooth, mobile data, airplane mode, brightness, volumes, DND, rotation, screen on/locked, foreground app.",
            category = "info",
            summary = { "Read device state" }
        ) {
            val script = """
                echo "battery=$(dumpsys battery | grep -m1 level | awk '{print $2}')% charging=$(dumpsys battery | grep -m1 'AC powered\|USB powered\|Wireless powered' | grep -c true)"
                echo "wifi=$(settings get global wifi_on) bluetooth=$(settings get global bluetooth_on) mobile_data=$(settings get global mobile_data) airplane=$(settings get global airplane_mode_on)"
                echo "brightness=$(settings get system screen_brightness)/255 auto_brightness=$(settings get system screen_brightness_mode)"
                echo "rotation_lock=$(( 1 - $(settings get system accelerometer_rotation) )) screen_timeout_ms=$(settings get system screen_off_timeout)"
                echo "dnd=$(settings get global zen_mode) dark_mode=$(cmd uimode night 2>/dev/null | grep -o 'yes\|no')"
                echo "screen_on=$(dumpsys power | grep -m1 'mWakefulness=' | cut -d= -f2) locked=$(dumpsys window 2>/dev/null | grep -m1 -o 'mDreamingLockscreen=true\|isStatusBarKeyguard=true' | grep -c true)"
                echo "volume_media=$(cmd media_session volume --stream 3 --get 2>/dev/null | grep -o 'volume is [0-9]*' | awk '{print $3}') ring=$(cmd media_session volume --stream 2 --get 2>/dev/null | grep -o 'volume is [0-9]*' | awk '{print $3}')"
                echo "wifi_ssid=$(dumpsys wifi 2>/dev/null | grep -m1 -o 'SSID: "[^"]*"' | cut -d'"' -f2)"
                echo "foreground=$(dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+')"
                echo "android=$(getprop ro.build.version.release) sdk=$(getprop ro.build.version.sdk) device=$(getprop ro.product.manufacturer) $(getprop ro.product.model)"
            """.trimIndent()
            val r = ShizukuShell.run(script)
            if (r.stdout.isBlank()) ToolResult.fail(r.text, "dumpsys/settings") else ToolResult.ok(r.stdout.trim(), "dumpsys/settings")
        },

        SimpleTool(
            name = "set_brightness",
            description = "Set screen brightness. Also disables auto-brightness.",
            params = listOf(Param("percent", "integer", "0-100")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Brightness ${a.int("percent")}%" }
        ) { a ->
            val pct = a.requireInt("percent").coerceIn(0, 100)
            val level = (pct * 255 / 100.0).toInt()
            simple("settings put system screen_brightness_mode 0; settings put system screen_brightness $level", "Brightness set to $pct%.")
        },

        SimpleTool(
            name = "set_auto_brightness",
            description = "Turn adaptive (auto) brightness on or off.",
            params = listOf(Param("enabled", "boolean", "")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Auto-brightness ${if (a.bool("enabled") == true) "on" else "off"}" }
        ) { a ->
            val on = a.requireBool("enabled")
            simple("settings put system screen_brightness_mode ${if (on) 1 else 0}", "Auto-brightness ${if (on) "enabled" else "disabled"}.")
        },

        SimpleTool(
            name = "set_volume",
            description = "Set volume for a stream as a percentage.",
            params = listOf(
                Param("percent", "integer", "0-100"),
                Param("stream", "string", "Which volume (default media)", required = false, choices = listOf("media", "ring", "alarm", "notification", "call", "system"))
            ),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "${a.string("stream") ?: "media"} volume ${a.int("percent")}%" }
        ) { a ->
            val pct = a.requireInt("percent").coerceIn(0, 100)
            val stream = when (a.string("stream")?.lowercase()) { "ring" -> 2; "alarm" -> 4; "notification" -> 5; "call" -> 0; "system" -> 1; else -> 3 }
            val max = ShizukuShell.run("cmd media_session volume --stream $stream --get 2>/dev/null | grep -o 'range is [0-9]*\\.\\.[0-9]*' | grep -o '[0-9]*$'").stdout.trim().toIntOrNull() ?: 15
            val level = Math.round(pct * max / 100.0).toInt()
            simple("cmd media_session volume --stream $stream --set $level --show", "Volume set to $pct% ($level/$max).")
        },

        SimpleTool(
            name = "set_wifi",
            description = "Turn Wi-Fi on or off.",
            params = listOf(Param("enabled", "boolean", "")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Wi-Fi ${if (a.bool("enabled") == true) "on" else "off"}" }
        ) { a ->
            val on = a.requireBool("enabled")
            simple("svc wifi ${if (on) "enable" else "disable"}", "Wi-Fi turned ${if (on) "on" else "off"}.")
        },

        SimpleTool(
            name = "connect_wifi",
            description = "Connect to a Wi-Fi network by SSID (Android 11+).",
            params = listOf(Param("ssid", "string", "Network name"), Param("password", "string", "Password (omit for open networks)", required = false)),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Connect Wi-Fi ${a.string("ssid")}" }
        ) { a ->
            val ssid = a.requireString("ssid")
            val pw = a.string("password")
            val cmd = if (pw.isNullOrBlank()) "cmd wifi connect-network ${q(ssid)} open" else "cmd wifi connect-network ${q(ssid)} wpa2 ${q(pw)}"
            val r = ShizukuShell.run("svc wifi enable; $cmd")
            delay(3000)
            val status = ShizukuShell.run("cmd wifi status | head -3").stdout.trim()
            if (r.ok) ToolResult.ok("Connection requested.\n$status", cmd) else ToolResult.fail(r.text, cmd)
        },

        SimpleTool(
            name = "list_wifi_networks",
            description = "Scan and list nearby Wi-Fi networks.",
            category = CAT,
            summary = { "Scan Wi-Fi" }
        ) {
            ShizukuShell.run("cmd wifi start-scan"); delay(2500)
            val r = ShizukuShell.run("cmd wifi list-scan-results | head -25")
            ToolResult.ok(r.stdout.ifBlank { "No results (Wi-Fi may be off)." }, r.command)
        },

        SimpleTool(
            name = "set_bluetooth",
            description = "Turn Bluetooth on or off.",
            params = listOf(Param("enabled", "boolean", "")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Bluetooth ${if (a.bool("enabled") == true) "on" else "off"}" }
        ) { a ->
            val on = a.requireBool("enabled")
            val r = ShizukuShell.run("svc bluetooth ${if (on) "enable" else "disable"} 2>&1 || cmd bluetooth_manager ${if (on) "enable" else "disable"} 2>&1")
            delay(800)
            val state = ShizukuShell.run("settings get global bluetooth_on").stdout.trim()
            if (state == (if (on) "1" else "0")) ToolResult.ok("Bluetooth turned ${if (on) "on" else "off"}.", r.command)
            else ToolResult.fail("Bluetooth state is $state after command. ${r.text}", r.command)
        },

        SimpleTool(
            name = "set_mobile_data",
            description = "Turn mobile data on or off.",
            params = listOf(Param("enabled", "boolean", "")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Mobile data ${if (a.bool("enabled") == true) "on" else "off"}" }
        ) { a ->
            val on = a.requireBool("enabled")
            simple("svc data ${if (on) "enable" else "disable"}", "Mobile data turned ${if (on) "on" else "off"}.")
        },

        SimpleTool(
            name = "set_airplane_mode",
            description = "Turn airplane mode on or off.",
            params = listOf(Param("enabled", "boolean", "")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Airplane mode ${if (a.bool("enabled") == true) "on" else "off"}" }
        ) { a ->
            val on = a.requireBool("enabled")
            val v = if (on) 1 else 0
            simple("cmd connectivity airplane-mode ${if (on) "enable" else "disable"} 2>/dev/null || (settings put global airplane_mode_on $v && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state ${on})",
                "Airplane mode ${if (on) "on" else "off"}.")
        },

        SimpleTool(
            name = "set_hotspot",
            description = "Turn the Wi-Fi hotspot (tethering) on or off.",
            params = listOf(Param("enabled", "boolean", "")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Hotspot ${if (a.bool("enabled") == true) "on" else "off"}" }
        ) { a ->
            val on = a.requireBool("enabled")
            simple("cmd connectivity ${if (on) "start-tethering" else "stop-tethering"} wifi 2>&1 || svc wifi ${if (on) "enable" else "disable"}", "Hotspot ${if (on) "started" else "stopped"}.")
        },

        SimpleTool(
            name = "set_location",
            description = "Turn location services on or off.",
            params = listOf(Param("enabled", "boolean", "")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Location ${if (a.bool("enabled") == true) "on" else "off"}" }
        ) { a ->
            val on = a.requireBool("enabled")
            simple("settings put secure location_mode ${if (on) 3 else 0}; cmd location set-location-enabled ${on} 2>/dev/null", "Location ${if (on) "enabled" else "disabled"}.")
        },

        SimpleTool(
            name = "set_nfc",
            description = "Turn NFC on or off.",
            params = listOf(Param("enabled", "boolean", "")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "NFC ${if (a.bool("enabled") == true) "on" else "off"}" }
        ) { a ->
            val on = a.requireBool("enabled")
            simple("svc nfc ${if (on) "enable" else "disable"}", "NFC ${if (on) "enabled" else "disabled"}.")
        },

        SimpleTool(
            name = "set_do_not_disturb",
            description = "Set Do Not Disturb mode.",
            params = listOf(Param("mode", "string", "", choices = listOf("off", "priority", "alarms_only", "total_silence"))),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "DND ${a.string("mode")}" }
        ) { a ->
            val v = when (a.requireString("mode").lowercase()) { "off" -> 0; "priority" -> 1; "total_silence" -> 2; "alarms_only" -> 3; else -> 0 }
            simple("settings put global zen_mode $v; cmd notification set_dnd ${listOf("off", "priority", "none", "alarms")[v]} 2>/dev/null", "Do Not Disturb set.")
        },

        SimpleTool(
            name = "set_ringer_mode",
            description = "Set ringer to normal, vibrate, or silent.",
            params = listOf(Param("mode", "string", "", choices = listOf("normal", "vibrate", "silent"))),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Ringer ${a.string("mode")}" }
        ) { a ->
            val mode = a.requireString("mode").lowercase()
            val cmd = when (mode) {
                "silent" -> "cmd media_session volume --stream 2 --set 0; settings put global zen_mode 2"
                "vibrate" -> "settings put global zen_mode 0; cmd media_session volume --stream 2 --set 0; cmd media_session volume --stream 2 --adj mute 2>/dev/null"
                else -> "settings put global zen_mode 0; cmd media_session volume --stream 2 --set 5"
            }
            simple(cmd, "Ringer set to $mode.")
        },

        SimpleTool(
            name = "set_rotation_lock",
            description = "Lock or unlock screen auto-rotation. Optionally set a fixed orientation.",
            params = listOf(
                Param("locked", "boolean", "true = lock rotation"),
                Param("orientation", "string", "When locked: portrait or landscape", required = false, choices = listOf("portrait", "landscape"))
            ),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Rotation ${if (a.bool("locked") == true) "locked" else "auto"}" }
        ) { a ->
            val locked = a.requireBool("locked")
            val rot = if (a.string("orientation") == "landscape") 1 else 0
            simple("settings put system accelerometer_rotation ${if (locked) 0 else 1}; ${if (locked) "settings put system user_rotation $rot" else "true"}",
                if (locked) "Rotation locked to ${a.string("orientation") ?: "portrait"}." else "Auto-rotate enabled.")
        },

        SimpleTool(
            name = "set_screen_timeout",
            description = "Set how long before the screen turns off.",
            params = listOf(Param("seconds", "integer", "15 to 1800")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Screen timeout ${a.int("seconds")}s" }
        ) { a ->
            val s = a.requireInt("seconds").coerceIn(15, 1800)
            simple("settings put system screen_off_timeout ${s * 1000}", "Screen timeout set to ${s}s.")
        },

        SimpleTool(
            name = "set_dark_mode",
            description = "Switch system dark/light theme.",
            params = listOf(Param("enabled", "boolean", "true = dark")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "${if (a.bool("enabled") == true) "Dark" else "Light"} mode" }
        ) { a ->
            val on = a.requireBool("enabled")
            simple("cmd uimode night ${if (on) "yes" else "no"}", "${if (on) "Dark" else "Light"} mode enabled.")
        },

        SimpleTool(
            name = "set_font_scale",
            description = "Set system font size scale (0.85 small, 1.0 default, 1.15 large, 1.3 largest).",
            params = listOf(Param("scale", "number", "0.8 - 1.5")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Font scale ${a.string("scale")}" }
        ) { a ->
            val s = (a.string("scale")?.toDoubleOrNull() ?: 1.0).coerceIn(0.8, 1.5)
            simple("settings put system font_scale $s", "Font scale set to $s.")
        },

        SimpleTool(
            name = "set_display_density",
            description = "Set display density (DPI). Use 'reset' to restore default. Changes UI size.",
            params = listOf(Param("dpi", "string", "Number like 420, or 'reset'")),
            risk = Risk.CONFIRM, category = CAT,
            summary = { a -> "Density ${a.string("dpi")}" }
        ) { a ->
            val v = a.requireString("dpi")
            simple(if (v == "reset") "wm density reset" else "wm density ${v.toIntOrNull()?.coerceIn(200, 700) ?: 420}", "Density updated.")
        },

        SimpleTool(
            name = "screen_power",
            description = "Wake the screen or turn it off (lock).",
            params = listOf(Param("on", "boolean", "true = wake up, false = screen off")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> if (a.bool("on") == true) "Wake screen" else "Screen off" }
        ) { a ->
            val on = a.requireBool("on")
            simple("input keyevent ${if (on) "KEYCODE_WAKEUP; input keyevent KEYCODE_MENU" else "KEYCODE_SLEEP"}", if (on) "Screen on." else "Screen off.")
        },

        SimpleTool(
            name = "set_battery_saver",
            description = "Turn battery saver on or off.",
            params = listOf(Param("enabled", "boolean", "")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Battery saver ${if (a.bool("enabled") == true) "on" else "off"}" }
        ) { a ->
            val on = a.requireBool("enabled")
            simple("settings put global low_power ${if (on) 1 else 0}; cmd power set-mode ${if (on) 1 else 0} 2>/dev/null", "Battery saver ${if (on) "on" else "off"}.")
        },

        SimpleTool(
            name = "open_settings_page",
            description = "Open a specific system Settings page.",
            params = listOf(Param("page", "string", "", choices = listOf(
                "main", "wifi", "bluetooth", "display", "sound", "battery", "apps", "storage", "security", "location", "accounts",
                "date", "language", "developer", "accessibility", "notifications", "privacy", "about", "network", "nfc", "vpn", "hotspot", "app_details"
            )), Param("app", "string", "For page=app_details: app name/package", required = false)),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Open Settings › ${a.string("page")}" }
        ) { a ->
            val page = a.requireString("page").lowercase()
            val action = settingsActions[page] ?: return@SimpleTool ToolResult.fail("Unknown page. Options: ${settingsActions.keys.joinToString()}")
            val cmd = if (page == "app_details") {
                val pkg = AppTools.resolvePackage(context, a.requireString("app")) ?: return@SimpleTool ToolResult.fail("App not found.")
                "am start -a $action -d package:$pkg"
            } else "am start -a $action"
            val r = ShizukuShell.run(cmd)
            delay(1200)
            if (r.ok && !r.stdout.contains("Error")) ToolResult.ok("Opened Settings › $page. Call get_screen to continue.", cmd) else ToolResult.fail(r.text, cmd)
        },

        SimpleTool(
            name = "open_url",
            description = "Open a URL in the default browser (or the app that handles it, e.g. a YouTube/maps link).",
            params = listOf(Param("url", "string", "Full URL including https://")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Open ${a.string("url")}" }
        ) { a ->
            var url = a.requireString("url")
            if (!url.contains("://")) url = "https://$url"
            val r = ShizukuShell.run("am start -a android.intent.action.VIEW -d ${q(url)}")
            delay(1500)
            if (r.ok && !r.stdout.contains("Error")) ToolResult.ok("Opened $url.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "send_intent",
            description = "Start an arbitrary activity via `am start`. Advanced: e.g. action=android.intent.action.SENDTO data=smsto:+1234 extras=\"--es sms_body Hello\".",
            params = listOf(
                Param("action", "string", "Intent action", required = false),
                Param("data", "string", "Data URI", required = false),
                Param("component", "string", "pkg/.Activity", required = false),
                Param("extras", "string", "Raw am extras string like --es key value", required = false)
            ),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Intent ${a.string("action") ?: a.string("component")}" }
        ) { a ->
            val parts = mutableListOf("am start")
            a.string("action")?.let { parts += "-a ${q(it)}" }
            a.string("data")?.let { parts += "-d ${q(it)}" }
            a.string("component")?.let { parts += "-n ${q(it)}" }
            a.string("extras")?.let { parts += it }
            if (parts.size == 1) return@SimpleTool ToolResult.fail("Provide at least action, data, or component.")
            val cmd = parts.joinToString(" ")
            val r = ShizukuShell.run(cmd)
            delay(1200)
            if (r.ok && !r.stdout.contains("Error")) ToolResult.ok("Intent sent.", cmd) else ToolResult.fail(r.text, cmd)
        },

        SimpleTool(
            name = "make_call",
            description = "Start a phone call to a number.",
            params = listOf(Param("number", "string", "Phone number")),
            risk = Risk.CONFIRM, category = "comm",
            summary = { a -> "Call ${a.string("number")}" }
        ) { a ->
            val n = a.requireString("number").filter { it.isDigit() || it == '+' }
            val r = ShizukuShell.run("am start -a android.intent.action.CALL -d tel:$n")
            if (r.ok && !r.stdout.contains("Error")) ToolResult.ok("Calling $n.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "compose_sms",
            description = "Open the SMS app with a recipient and message pre-filled. The user (or you via get_screen/tap) must press send.",
            params = listOf(Param("number", "string", "Recipient"), Param("message", "string", "Body")),
            risk = Risk.LOW, category = "comm",
            summary = { a -> "SMS to ${a.string("number")}" }
        ) { a ->
            val n = a.requireString("number").filter { it.isDigit() || it == '+' }
            val body = a.requireString("message")
            val r = ShizukuShell.run("am start -a android.intent.action.SENDTO -d smsto:$n --es sms_body ${q(body)}")
            delay(1500)
            if (r.ok && !r.stdout.contains("Error")) ToolResult.ok("SMS composer opened. Call get_screen and tap Send.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "set_alarm",
            description = "Create an alarm via the clock app.",
            params = listOf(Param("hour", "integer", "0-23"), Param("minute", "integer", "0-59"), Param("label", "string", "", required = false)),
            risk = Risk.LOW, category = "comm",
            summary = { a -> "Alarm ${a.int("hour")}:${"%02d".format(a.int("minute") ?: 0)}" }
        ) { a ->
            val h = a.requireInt("hour").coerceIn(0, 23); val m = (a.int("minute") ?: 0).coerceIn(0, 59)
            val label = a.string("label")?.let { "--es android.intent.extra.alarm.MESSAGE ${q(it)}" } ?: ""
            val r = ShizukuShell.run("am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR $h --ei android.intent.extra.alarm.MINUTES $m --ez android.intent.extra.alarm.SKIP_UI true $label")
            if (r.ok && !r.stdout.contains("Error")) ToolResult.ok("Alarm set for %d:%02d.".format(h, m), r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "set_timer",
            description = "Start a countdown timer.",
            params = listOf(Param("seconds", "integer", "Duration"), Param("label", "string", "", required = false)),
            risk = Risk.LOW, category = "comm",
            summary = { a -> "Timer ${a.int("seconds")}s" }
        ) { a ->
            val s = a.requireInt("seconds").coerceAtLeast(1)
            val label = a.string("label")?.let { "--es android.intent.extra.alarm.MESSAGE ${q(it)}" } ?: ""
            val r = ShizukuShell.run("am start -a android.intent.action.SET_TIMER --ei android.intent.extra.alarm.LENGTH $s --ez android.intent.extra.alarm.SKIP_UI true $label")
            if (r.ok && !r.stdout.contains("Error")) ToolResult.ok("Timer started (${s}s).", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "set_clipboard",
            description = "Put text on the clipboard.",
            params = listOf(Param("text", "string", "")),
            risk = Risk.LOW, category = "info",
            summary = { a -> "Copy to clipboard" }
        ) { a ->
            val t = a.requireString("text")
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("DevicePilot", t))
                ToolResult.ok("Copied ${t.length} characters.")
            } catch (e: Throwable) { ToolResult.fail("Clipboard error: ${e.message}") }
        },

        SimpleTool(
            name = "get_storage_info",
            description = "Show storage usage (total/used/free) and the largest folders in shared storage.",
            category = "info",
            summary = { "Storage info" }
        ) {
            val r = ShizukuShell.run("df -h /data | tail -1 | awk '{print \"data: size=\"$2\" used=\"$3\" free=\"$4\" (\"$5\")\"}'; du -sh /sdcard/* 2>/dev/null | sort -rh | head -10")
            ToolResult.ok(r.stdout.ifBlank { r.text }, r.command)
        },

        SimpleTool(
            name = "list_files",
            description = "List files in a directory on shared storage (e.g. /sdcard/Download).",
            params = listOf(Param("path", "string", "Directory path")),
            category = "files",
            summary = { a -> "ls ${a.string("path")}" }
        ) { a ->
            val p = a.requireString("path")
            ShellGuard.checkPath(p)?.let { return@SimpleTool ToolResult.fail(it) }
            val r = ShizukuShell.run("ls -la ${q(p)} | head -60")
            if (r.ok) ToolResult.ok(r.stdout.ifBlank { "(empty)" }, r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "read_file",
            description = "Read a text file (first 4 KB).",
            params = listOf(Param("path", "string", "")),
            category = "files",
            summary = { a -> "Read ${a.string("path")}" }
        ) { a ->
            val p = a.requireString("path")
            ShellGuard.checkPath(p)?.let { return@SimpleTool ToolResult.fail(it) }
            val r = ShizukuShell.run("head -c 4096 ${q(p)}")
            if (r.ok) ToolResult.ok(r.stdout, r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "write_file",
            description = "Write text to a file on shared storage (overwrites).",
            params = listOf(Param("path", "string", ""), Param("content", "string", "")),
            risk = Risk.CONFIRM, category = "files",
            summary = { a -> "Write ${a.string("path")}" }
        ) { a ->
            val p = a.requireString("path")
            ShellGuard.checkPath(p)?.let { return@SimpleTool ToolResult.fail(it) }
            val r = ShizukuShell.run("printf '%s' ${q(a.requireString("content"))} > ${q(p)}")
            if (r.ok) ToolResult.ok("Wrote ${p}.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "delete_file",
            description = "Delete a file or empty directory on shared storage. Requires confirmation.",
            params = listOf(Param("path", "string", "")),
            risk = Risk.CONFIRM, category = "files",
            summary = { a -> "Delete ${a.string("path")}" }
        ) { a ->
            val p = a.requireString("path")
            ShellGuard.checkPath(p)?.let { return@SimpleTool ToolResult.fail(it) }
            val r = ShizukuShell.run("rm -d ${q(p)}")
            if (r.ok) ToolResult.ok("Deleted $p.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "reboot",
            description = "Reboot the device. Requires confirmation.",
            risk = Risk.CONFIRM, category = CAT,
            summary = { "Reboot device" }
        ) {
            val r = ShizukuShell.run("svc power reboot 2>&1 || reboot")
            if (r.ok) ToolResult.ok("Rebooting.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "shell",
            description = "Run an arbitrary ADB shell command (use only when no dedicated tool exists). Dangerous commands are blocked or require confirmation.",
            params = listOf(Param("command", "string", "Shell command")),
            risk = Risk.CONFIRM, category = "advanced",
            summary = { a -> "$ ${a.string("command")?.take(60)}" }
        ) { a ->
            val cmd = a.requireString("command")
            ShellGuard.check(cmd)?.let { return@SimpleTool ToolResult.fail(it) }
            val r = ShizukuShell.run(cmd, timeoutMs = 30_000)
            val out = (r.stdout + (if (r.stderr.isNotBlank()) "\n[stderr] " + r.stderr else "")).trim().take(3000)
            if (r.ok) ToolResult.ok(out.ifBlank { "OK (no output)" }, cmd) else ToolResult.fail(out.ifBlank { "exit ${r.exitCode}" }, cmd)
        }
    )

    private val settingsActions = mapOf(
        "main" to "android.settings.SETTINGS", "wifi" to "android.settings.WIFI_SETTINGS", "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "display" to "android.settings.DISPLAY_SETTINGS", "sound" to "android.settings.SOUND_SETTINGS", "battery" to "android.intent.action.POWER_USAGE_SUMMARY",
        "apps" to "android.settings.APPLICATION_SETTINGS", "storage" to "android.settings.INTERNAL_STORAGE_SETTINGS", "security" to "android.settings.SECURITY_SETTINGS",
        "location" to "android.settings.LOCATION_SOURCE_SETTINGS", "accounts" to "android.settings.SYNC_SETTINGS", "date" to "android.settings.DATE_SETTINGS",
        "language" to "android.settings.LOCALE_SETTINGS", "developer" to "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
        "notifications" to "android.settings.NOTIFICATION_SETTINGS", "privacy" to "android.settings.PRIVACY_SETTINGS", "about" to "android.settings.DEVICE_INFO_SETTINGS",
        "network" to "android.settings.WIRELESS_SETTINGS", "nfc" to "android.settings.NFC_SETTINGS", "vpn" to "android.settings.VPN_SETTINGS",
        "hotspot" to "android.settings.TETHER_SETTINGS", "app_details" to "android.settings.APPLICATION_DETAILS_SETTINGS"
    )
}
