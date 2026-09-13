package com.devicepilot.ai.tools

import android.content.Context
import com.devicepilot.ai.shizuku.ShizukuShell
import com.devicepilot.ai.shizuku.ShizukuShell.q
import kotlinx.coroutines.delay
import java.io.File

/** Perception + UI interaction tools (screen reading, tap, type, swipe, keys). */
object UiTools {

    private const val CAT = "screen"

    fun all(context: Context): List<Tool> = listOf(

        SimpleTool(
            name = "get_screen",
            description = "Read the current screen: foreground app and an indexed list of visible UI elements (buttons, text, inputs) with coordinates. ALWAYS call this before tapping or typing, and again after an action to verify the result.",
            category = CAT,
            summary = { "Read screen" }
        ) {
            ScreenReader.capture().fold(
                onSuccess = { ToolResult.ok(it.toPromptText(), "uiautomator dump") },
                onFailure = { ToolResult.fail(it.message ?: "screen read failed", "uiautomator dump") }
            )
        },

        SimpleTool(
            name = "tap_element",
            description = "Tap a UI element by its index from the most recent get_screen result.",
            params = listOf(Param("index", "integer", "Element index from get_screen")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Tap element #${a.int("index")}" }
        ) { a ->
            val idx = a.requireInt("index")
            val snap = ScreenReader.lastSnapshot ?: return@SimpleTool ToolResult.fail("No screen snapshot. Call get_screen first.")
            val el = snap.find(idx) ?: return@SimpleTool ToolResult.fail("No element with index $idx. Call get_screen again.")
            val r = ShizukuShell.run("input tap ${el.centerX} ${el.centerY}")
            delay(600)
            if (r.ok) ToolResult.ok("Tapped [${idx}] \"${el.label}\" at ${el.centerX},${el.centerY}. Call get_screen to see the result.", r.command)
            else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "tap",
            description = "Tap at absolute screen coordinates. Prefer tap_element when an index is available.",
            params = listOf(Param("x", "integer", "X pixel"), Param("y", "integer", "Y pixel")),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Tap ${a.int("x")},${a.int("y")}" }
        ) { a ->
            val r = ShizukuShell.run("input tap ${a.requireInt("x")} ${a.requireInt("y")}")
            delay(500)
            if (r.ok) ToolResult.ok("Tapped.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "long_press",
            description = "Long-press a UI element by index (from get_screen).",
            params = listOf(
                Param("index", "integer", "Element index"),
                Param("duration_ms", "integer", "Hold duration, default 800", required = false)
            ),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Long-press element #${a.int("index")}" }
        ) { a ->
            val snap = ScreenReader.lastSnapshot ?: return@SimpleTool ToolResult.fail("Call get_screen first.")
            val el = snap.find(a.requireInt("index")) ?: return@SimpleTool ToolResult.fail("Unknown element index.")
            val d = a.int("duration_ms") ?: 800
            val r = ShizukuShell.run("input swipe ${el.centerX} ${el.centerY} ${el.centerX} ${el.centerY} $d")
            delay(500)
            if (r.ok) ToolResult.ok("Long-pressed \"${el.label}\".", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "type_text",
            description = "Type text into the currently focused input field. Tap the input first so it has focus. Use \\n at the end to press Enter.",
            params = listOf(
                Param("text", "string", "Text to type"),
                Param("clear_first", "boolean", "Select-all and delete existing text before typing (default false)", required = false)
            ),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Type \"${a.string("text")?.take(40)}\"" }
        ) { a ->
            val raw = a.requireString("text")
            val pressEnter = raw.endsWith("\n")
            val text = raw.trimEnd('\n')
            if (a.bool("clear_first") == true) {
                // Move caret to end, then send a burst of DEL keyevents (input accepts many keycodes per call).
                val dels = (1..60).joinToString(" ") { "KEYCODE_DEL" }
                ShizukuShell.run("input keyevent KEYCODE_MOVE_END; input keyevent $dels")
            }
            // `input text` cannot take raw spaces; %s is decoded to a space. Quote for sh.
            val safe = text.replace(" ", "%s")
            val hasNonAscii = text.any { it.code > 127 }
            val r = ShizukuShell.run("input text ${q(safe)}")
            if (pressEnter) ShizukuShell.run("input keyevent KEYCODE_ENTER")
            delay(400)
            if (r.ok) {
                val note = if (hasNonAscii) " (note: non-ASCII characters may not be typed correctly by the system input pipe)" else ""
                ToolResult.ok("Typed text$note.${if (pressEnter) " Pressed Enter." else ""}", r.command)
            } else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "press_key",
            description = "Press a hardware/navigation key.",
            params = listOf(Param("key", "string", "Key name", choices = listOf(
                "back", "home", "recents", "enter", "delete", "tab", "power", "volume_up", "volume_down", "mute",
                "menu", "search", "camera", "call", "end_call", "media_play_pause", "media_next", "media_previous",
                "notifications", "quick_settings", "wake", "sleep", "dpad_up", "dpad_down", "dpad_left", "dpad_right", "escape", "app_switch"))),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Press ${a.string("key")}" }
        ) { a ->
            val key = a.requireString("key").lowercase()
            val r = when (key) {
                "notifications" -> ShizukuShell.run("cmd statusbar expand-notifications")
                "quick_settings" -> ShizukuShell.run("cmd statusbar expand-settings")
                else -> {
                    val code = keyMap[key] ?: return@SimpleTool ToolResult.fail("Unknown key '$key'. Valid: ${keyMap.keys.joinToString()}")
                    ShizukuShell.run("input keyevent $code")
                }
            }
            delay(500)
            if (r.ok) ToolResult.ok("Pressed $key.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "swipe",
            description = "Swipe/scroll the screen in a direction. Use to scroll lists or dismiss.",
            params = listOf(
                Param("direction", "string", "Direction the content should move to reveal more", choices = listOf("up", "down", "left", "right")),
                Param("distance", "string", "short | medium | long (default medium)", required = false),
                Param("index", "integer", "Optional element index to swipe inside (a scrollable list)", required = false)
            ),
            risk = Risk.LOW, category = CAT,
            summary = { a -> "Swipe ${a.string("direction")}" }
        ) { a ->
            val dir = a.requireString("direction").lowercase()
            val snap = ScreenReader.lastSnapshot
            val el = a.int("index")?.let { snap?.find(it) }
            val (w, h) = if (snap != null) snap.width to snap.height else ScreenReader.displaySize()
            val cx = el?.centerX ?: (w / 2)
            val cy = el?.centerY ?: (h / 2)
            val frac = when (a.string("distance")?.lowercase()) { "short" -> 0.15; "long" -> 0.4; else -> 0.28 }
            val dx = (w * frac).toInt(); val dy = (h * frac).toInt()
            // "up" = content moves up = finger moves up (reveals content below)
            val (x1, y1, x2, y2) = when (dir) {
                "up" -> listOf(cx, cy + dy / 2, cx, cy - dy / 2)
                "down" -> listOf(cx, cy - dy / 2, cx, cy + dy / 2)
                "left" -> listOf(cx + dx / 2, cy, cx - dx / 2, cy)
                "right" -> listOf(cx - dx / 2, cy, cx + dx / 2, cy)
                else -> return@SimpleTool ToolResult.fail("direction must be up/down/left/right")
            }
            val r = ShizukuShell.run("input swipe $x1 $y1 $x2 $y2 300")
            delay(700)
            if (r.ok) ToolResult.ok("Swiped $dir. Call get_screen to see new content.", r.command) else ToolResult.fail(r.text, r.command)
        },

        SimpleTool(
            name = "screenshot",
            description = "Capture a screenshot to a file (for the user to view). Does not return image content to you; use get_screen to read UI.",
            category = CAT,
            summary = { "Take screenshot" }
        ) {
            val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            val f = File(dir, "shot_${System.currentTimeMillis()}.png")
            val r = ShizukuShell.runToFile("screencap -p", f)
            if (f.exists() && f.length() > 1000) ToolResult.ok("Screenshot saved (${f.length() / 1024} KB).", "screencap -p", f.absolutePath)
            else ToolResult.fail("Screenshot failed: ${r.stderr}", "screencap -p")
        },

        SimpleTool(
            name = "wait",
            description = "Wait for the UI to settle (e.g. after opening an app or loading).",
            params = listOf(Param("seconds", "number", "Seconds to wait, max 10", required = false)),
            category = CAT,
            summary = { a -> "Wait ${a.string("seconds") ?: "1.5"}s" }
        ) { a ->
            val s = (a.string("seconds")?.toDoubleOrNull() ?: 1.5).coerceIn(0.2, 10.0)
            delay((s * 1000).toLong())
            ToolResult.ok("Waited ${s}s.")
        },

        SimpleTool(
            name = "get_notifications",
            description = "List current notifications (app, title, text).",
            category = "info",
            summary = { "Read notifications" }
        ) {
            val r = ShizukuShell.run("dumpsys notification --noredact 2>/dev/null || dumpsys notification")
            if (!r.ok && r.stdout.isBlank()) return@SimpleTool ToolResult.fail(r.text, r.command)
            val out = parseNotifications(r.stdout)
            ToolResult.ok(if (out.isBlank()) "No notifications." else out, "dumpsys notification")
        },

        SimpleTool(
            name = "dismiss_notifications",
            description = "Clear all notifications.",
            risk = Risk.LOW, category = "info",
            summary = { "Clear all notifications" }
        ) {
            val r = ShizukuShell.run("cmd notification cancel_all 2>/dev/null || service call notification 1")
            if (r.ok) ToolResult.ok("Notifications cleared.", r.command) else ToolResult.fail(r.text, r.command)
        }
    )

    private val keyMap = mapOf(
        "back" to "KEYCODE_BACK", "home" to "KEYCODE_HOME", "recents" to "KEYCODE_APP_SWITCH", "app_switch" to "KEYCODE_APP_SWITCH",
        "enter" to "KEYCODE_ENTER", "delete" to "KEYCODE_DEL", "tab" to "KEYCODE_TAB", "power" to "KEYCODE_POWER",
        "volume_up" to "KEYCODE_VOLUME_UP", "volume_down" to "KEYCODE_VOLUME_DOWN", "mute" to "KEYCODE_VOLUME_MUTE",
        "menu" to "KEYCODE_MENU", "search" to "KEYCODE_SEARCH", "camera" to "KEYCODE_CAMERA", "call" to "KEYCODE_CALL",
        "end_call" to "KEYCODE_ENDCALL", "media_play_pause" to "KEYCODE_MEDIA_PLAY_PAUSE", "media_next" to "KEYCODE_MEDIA_NEXT",
        "media_previous" to "KEYCODE_MEDIA_PREVIOUS", "wake" to "KEYCODE_WAKEUP", "sleep" to "KEYCODE_SLEEP",
        "dpad_up" to "KEYCODE_DPAD_UP", "dpad_down" to "KEYCODE_DPAD_DOWN", "dpad_left" to "KEYCODE_DPAD_LEFT",
        "dpad_right" to "KEYCODE_DPAD_RIGHT", "escape" to "KEYCODE_ESCAPE"
    )

    private fun parseNotifications(dump: String): String {
        val sb = StringBuilder()
        var pkg = ""
        var title = ""
        var text = ""
        var count = 0
        fun flush() {
            if (pkg.isNotBlank() && (title.isNotBlank() || text.isNotBlank()) && count < 25) {
                sb.append("- ").append(pkg).append(": ").append(title.take(60))
                if (text.isNotBlank()) sb.append(" — ").append(text.take(100))
                sb.append('\n'); count++
            }
            pkg = ""; title = ""; text = ""
        }
        for (line in dump.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("NotificationRecord(") -> {
                    flush()
                    pkg = Regex("""pkg=([\w.]+)""").find(t)?.groupValues?.get(1) ?: ""
                }
                t.startsWith("android.title=") -> title = t.substringAfter("=").removePrefix("String (").removeSuffix(")").trim()
                t.startsWith("android.text=") -> text = t.substringAfter("=").removePrefix("String (").removeSuffix(")").trim()
            }
        }
        flush()
        return sb.toString().trimEnd()
    }
}
