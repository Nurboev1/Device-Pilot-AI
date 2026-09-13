package com.devicepilot.ai.tools

import com.devicepilot.ai.shizuku.ShizukuShell
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class UiElement(
    val index: Int,
    val text: String,
    val contentDesc: String,
    val resourceId: String,
    val className: String,
    val bounds: IntArray,   // left, top, right, bottom
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val focused: Boolean,
    val enabled: Boolean
) {
    val centerX: Int get() = (bounds[0] + bounds[2]) / 2
    val centerY: Int get() = (bounds[1] + bounds[3]) / 2
    val label: String get() = text.ifBlank { contentDesc }.ifBlank { resourceId.substringAfterLast('/') }

    /** One compact line for the model. */
    fun toPromptLine(): String {
        val sb = StringBuilder()
        sb.append('[').append(index).append("] ")
        val kind = when {
            editable -> "input"
            className.endsWith("Button") || className.endsWith("ImageButton") -> "button"
            className.endsWith("CheckBox") || className.endsWith("Switch") || className.endsWith("ToggleButton") -> "toggle"
            className.endsWith("EditText") -> "input"
            className.endsWith("ImageView") -> "image"
            className.endsWith("TextView") -> "text"
            scrollable -> "scrollable"
            else -> className.substringAfterLast('.').lowercase()
        }
        sb.append(kind)
        if (text.isNotBlank()) sb.append(" \"").append(text.take(60).replace('\n', ' ')).append('"')
        if (contentDesc.isNotBlank() && contentDesc != text) sb.append(" desc=\"").append(contentDesc.take(40)).append('"')
        val rid = resourceId.substringAfterLast('/')
        if (rid.isNotBlank() && rid != text) sb.append(" id=").append(rid.take(30))
        checked?.let { sb.append(if (it) " checked" else " unchecked") }
        if (focused) sb.append(" focused")
        if (!enabled) sb.append(" disabled")
        if (!clickable && !editable && !scrollable) sb.append(" (not clickable)")
        sb.append(" @").append(centerX).append(',').append(centerY)
        return sb.toString()
    }
}

data class ScreenSnapshot(
    val packageName: String,
    val activity: String,
    val elements: List<UiElement>,
    val width: Int,
    val height: Int,
    val keyboardShown: Boolean
) {
    fun find(index: Int): UiElement? = elements.firstOrNull { it.index == index }

    fun toPromptText(maxElements: Int = 60): String {
        val sb = StringBuilder()
        sb.append("app=").append(packageName)
        if (activity.isNotBlank()) sb.append(" activity=").append(activity.substringAfterLast('.'))
        sb.append(" screen=").append(width).append('x').append(height)
        if (keyboardShown) sb.append(" keyboard=shown")
        sb.append('\n')
        if (elements.isEmpty()) {
            sb.append("(no UI elements found — screen may be locked, loading, or a secure surface)")
        } else {
            elements.take(maxElements).forEach { sb.append(it.toPromptLine()).append('\n') }
            if (elements.size > maxElements) sb.append("... and ${elements.size - maxElements} more elements (scroll to reveal)\n")
        }
        return sb.toString().trimEnd()
    }
}

/**
 * Captures the UI hierarchy via `uiautomator dump` and reduces it to a compact
 * indexed list of interactive/informative elements.
 */
object ScreenReader {

    @Volatile var lastSnapshot: ScreenSnapshot? = null
        private set

    private val boundsRegex = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    suspend fun capture(): Result<ScreenSnapshot> {
        // Hide our floating Stop pill so it doesn't show up in the hierarchy.
        val overlay = com.devicepilot.ai.service.StopOverlay.current
        overlay?.setVisible(false)
        try {
            return captureInternal()
        } finally {
            overlay?.setVisible(true)
        }
    }

    private suspend fun captureInternal(): Result<ScreenSnapshot> {
        kotlinx.coroutines.delay(120)
        // Write to /data/local/tmp (shell-writable everywhere) and cat it back.
        val dump = ShizukuShell.run(
            "uiautomator dump /data/local/tmp/dp_ui.xml >/dev/null 2>&1; cat /data/local/tmp/dp_ui.xml",
            timeoutMs = 15_000
        )
        if (!dump.ok && dump.stdout.isBlank()) {
            return Result.failure(IllegalStateException("uiautomator dump failed: ${dump.stderr.ifBlank { "no output" }}"))
        }
        val xml = dump.stdout
        if (!xml.contains("<hierarchy")) {
            return Result.failure(IllegalStateException("UI dump returned no hierarchy: ${xml.take(200)}"))
        }

        val focus = currentFocus()
        val (w, h) = displaySize()
        val elements = parse(xml)
        val kb = ShizukuShell.run("dumpsys input_method | grep -m1 -E 'mInputShown=true|isInputViewShown=true'").stdout.isNotBlank()

        val snap = ScreenSnapshot(focus.first, focus.second, elements, w, h, kb)
        lastSnapshot = snap
        return Result.success(snap)
    }

    suspend fun currentFocus(): Pair<String, String> {
        val r = ShizukuShell.run("dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -2")
        val m = Regex("""([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)""").find(r.stdout) ?: return "unknown" to ""
        return m.groupValues[1] to m.groupValues[2]
    }

    suspend fun displaySize(): Pair<Int, Int> {
        val r = ShizukuShell.run("wm size")
        val m = Regex("""(\d+)x(\d+)""").findAll(r.stdout).lastOrNull() ?: return 1080 to 2400
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun parse(xml: String): List<UiElement> {
        val out = mutableListOf<UiElement>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var idx = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                fun a(n: String) = parser.getAttributeValue(null, n) ?: ""
                val text = a("text")
                val desc = a("content-desc")
                val rid = a("resource-id")
                val cls = a("class")
                val clickable = a("clickable") == "true"
                val longClickable = a("long-clickable") == "true"
                val scrollable = a("scrollable") == "true"
                val checkable = a("checkable") == "true"
                val focused = a("focused") == "true"
                val enabled = a("enabled") != "false"
                val editable = cls.endsWith("EditText") || cls.contains("AutoCompleteTextView")
                val bm = boundsRegex.find(a("bounds"))
                val b = if (bm != null) IntArray(4) { bm.groupValues[it + 1].toInt() } else intArrayOf(0, 0, 0, 0)
                val area = (b[2] - b[0]) * (b[3] - b[1])
                val interesting = (clickable || longClickable || editable || scrollable || checkable ||
                        text.isNotBlank() || desc.isNotBlank()) && area > 0
                if (interesting) {
                    out += UiElement(
                        index = idx++,
                        text = text, contentDesc = desc, resourceId = rid, className = cls,
                        bounds = b, clickable = clickable || longClickable || checkable,
                        editable = editable, scrollable = scrollable,
                        checked = if (checkable) a("checked") == "true" else null,
                        focused = focused, enabled = enabled
                    )
                }
            }
            event = parser.next()
        }
        // Drop pure container text nodes that duplicate a clickable child with the same label.
        val labelsOfClickable = out.filter { it.clickable }.map { it.label }.toSet()
        return out.filterNot { !it.clickable && !it.editable && !it.scrollable && it.label in labelsOfClickable && it.className.endsWith("TextView") }
            .mapIndexed { i, e -> e.copy(index = i) }
    }
}
