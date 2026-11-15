package org.schabi.newpipe.pipevr.captions

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * PipeVR: YouTube's "srv3" timed text, the format styled captions are uploaded in (for example
 * from YTSubConverter): pens (font, colours, opacity, outline/shadow, size, bold/italic/
 * underline, sub/superscript), window positions (anchor point + position on the video) and
 * window styles (justification). Effects such as karaoke or moving text are many short events,
 * which the renderer handles by only redrawing when the set of visible events changes.
 */
object Srv3 {

    class Pen(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val fg: Int = Color.WHITE,
        val fgAlpha: Int = 254,
        val bg: Int = 0x080808,
        val bgAlpha: Int = 191,
        val edgeType: Int = 0,          // 0 none, 1 hard shadow, 2 bevel, 3 glow/outline, 4 soft shadow
        val edgeColor: Int = Color.BLACK,
        val font: Int = 0,              // fs
        val size: Int = 100,            // sz (percent)
        val offset: Int = 1,            // of: 0 subscript, 1 normal, 2 superscript
    )

    class WinPos(val anchor: Int = 7, val h: Int = 50, val v: Int = 100)
    class WinStyle(val justify: Int = 2)      // 0 left, 1 right, 2 centre (3 = justify → centre)

    class Segment(val text: String, val pen: Pen)

    class Event(
        val start: Long,
        val end: Long,
        val pos: WinPos,
        val style: WinStyle,
        val segments: List<Segment>,
    )

    class Track(val events: List<Event>, val styled: Boolean) {
        /** Longest event, to bound the backwards scan when looking up what is visible. */
        val maxDuration: Long = events.maxOfOrNull { it.end - it.start } ?: 0

        /** Indexes of the events visible at [timeMs], in file order (later ones on top). */
        fun activeAt(timeMs: Long, out: MutableList<Int>) {
            out.clear()
            var lo = 0
            var hi = events.size - 1
            var last = -1
            while (lo <= hi) {            // last event starting at or before timeMs
                val mid = (lo + hi) ushr 1
                if (events[mid].start <= timeMs) {
                    last = mid; lo = mid + 1
                } else hi = mid - 1
            }
            var i = last
            while (i >= 0 && events[i].start >= timeMs - maxDuration) {
                if (events[i].end > timeMs) out.add(i)
                i--
            }
            out.reverse()
        }
    }

    private fun color(s: String?, def: Int): Int {
        if (s.isNullOrEmpty()) return def
        return try {
            Color.parseColor(if (s.startsWith("#")) s else "#$s") and 0xFFFFFF
        } catch (e: Exception) {
            def
        }
    }

    private fun int(s: String?, def: Int): Int = s?.trim()?.toIntOrNull() ?: def

    fun parse(xml: String): Track {
        val pens = HashMap<String, Pen>()
        val wps = HashMap<String, WinPos>()
        val wss = HashMap<String, WinStyle>()
        val events = ArrayList<Event>()
        var styled = false
        val defaultPen = Pen()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))

        var inP = false
        var pStart = 0L
        var pEnd = 0L
        var pPen = defaultPen
        var pPos = WinPos()
        var pStyle = WinStyle()
        var segs = ArrayList<Segment>()
        val penStack = ArrayList<Pen>()
        val text = StringBuilder()

        fun flush(pen: Pen) {
            if (text.isNotEmpty()) {
                segs.add(Segment(text.toString(), pen))
                text.setLength(0)
            }
        }

        var type = p.eventType
        while (type != XmlPullParser.END_DOCUMENT) {
            when (type) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "pen" -> {
                        styled = true
                        val a = { n: String -> p.getAttributeValue(null, n) }
                        pens[a("id") ?: ""] = Pen(
                            bold = a("b") == "1",
                            italic = a("i") == "1",
                            underline = a("u") == "1",
                            fg = color(a("fc"), Color.WHITE and 0xFFFFFF),
                            fgAlpha = int(a("fo"), 254),
                            bg = color(a("bc"), 0x080808),
                            bgAlpha = int(a("bo"), 191),
                            edgeType = int(a("et"), 0),
                            edgeColor = color(a("ec"), 0x000000),
                            font = int(a("fs"), 0),
                            size = int(a("sz"), 100),
                            offset = int(a("of"), 1),
                        )
                    }
                    "wp" -> {
                        styled = true
                        wps[p.getAttributeValue(null, "id") ?: ""] = WinPos(
                            int(p.getAttributeValue(null, "ap"), 7),
                            int(p.getAttributeValue(null, "ah"), 50),
                            int(p.getAttributeValue(null, "av"), 100),
                        )
                    }
                    "ws" -> {
                        styled = true
                        wss[p.getAttributeValue(null, "id") ?: ""] =
                            WinStyle(int(p.getAttributeValue(null, "ju"), 2))
                    }
                    "p" -> {
                        inP = true
                        pStart = int(p.getAttributeValue(null, "t"), 0).toLong()
                        pEnd = pStart + int(p.getAttributeValue(null, "d"), 0).toLong()
                        pPen = pens[p.getAttributeValue(null, "p")] ?: defaultPen
                        pPos = wps[p.getAttributeValue(null, "wp")] ?: WinPos()
                        pStyle = wss[p.getAttributeValue(null, "ws")] ?: WinStyle()
                        segs = ArrayList()
                        penStack.clear()
                        text.setLength(0)
                    }
                    "s" -> if (inP) {
                        flush(penStack.lastOrNull() ?: pPen)
                        penStack.add(pens[p.getAttributeValue(null, "p")]
                            ?: penStack.lastOrNull() ?: pPen)
                    }
                    "br" -> if (inP) text.append('\n')
                }
                XmlPullParser.TEXT -> if (inP) text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "s" -> if (inP) {
                        flush(penStack.lastOrNull() ?: pPen)
                        if (penStack.isNotEmpty()) penStack.removeAt(penStack.size - 1)
                    }
                    "p" -> if (inP) {
                        flush(penStack.lastOrNull() ?: pPen)
                        inP = false
                        if (pEnd > pStart && segs.any { it.text.isNotBlank() }) {
                            events.add(Event(pStart, pEnd, pPos, pStyle, segs))
                        }
                    }
                }
            }
            type = p.next()
        }
        events.sortBy { it.start }   // stable: equal starts keep file order (later on top)
        return Track(events, styled)
    }

    // ------------------------------------------------------------------ rendering

    private val faces = HashMap<Int, Typeface>()

    private fun typeface(pen: Pen): Typeface {
        val key = pen.font * 4 + (if (pen.bold) 1 else 0) + (if (pen.italic) 2 else 0)
        return faces.getOrPut(key) {
            val family = when (pen.font) {
                1, 3 -> "monospace"                     // Courier New, Lucida Console
                2 -> "serif"                            // Times New Roman
                5 -> "casual"                           // Comic Sans
                6 -> "cursive"                          // Monotype Corsiva
                7 -> "sans-serif-smallcaps"             // Carrois Gothic SC
                else -> "sans-serif"                    // Roboto / YouTube's default
            }
            val style = when {
                pen.bold && pen.italic -> Typeface.BOLD_ITALIC
                pen.bold -> Typeface.BOLD
                pen.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(family, style)
        }
    }

    private class Run(val text: String, val pen: Pen, val paint: Paint, val width: Float)
    private class Line(val runs: List<Run>, val width: Float, val ascent: Float, val descent: Float)

    /**
     * Draws the events [active] (indexes into [track]) onto [bitmap], which covers the whole
     * video picture. Clears it first.
     */
    fun render(track: Track, active: List<Int>, bitmap: Bitmap) {
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val base = h * 0.041f          // YouTube's 100% caption size relative to the video height
        val bgPaint = Paint()
        for (index in active) {
            val ev = track.events[index]
            // lay the event out in lines of styled runs
            val lines = ArrayList<Line>()
            var runs = ArrayList<Run>()
            var lineW = 0f
            var asc = 0f
            var desc = 0f
            fun endLine() {
                if (asc == 0f) { // empty line: height of the default size
                    asc = base * 0.93f; desc = base * 0.25f
                }
                lines.add(Line(runs, lineW, asc, desc))
                runs = ArrayList(); lineW = 0f; asc = 0f; desc = 0f
            }
            for (seg in ev.segments) {
                val parts = seg.text.split('\n')
                for ((pi, part) in parts.withIndex()) {
                    if (pi > 0) endLine()
                    if (part.isEmpty()) continue
                    val pen = seg.pen
                    // YouTube compresses size changes: 200% shows as 125%
                    var size = base * (1f + (pen.size / 100f - 1f) / 4f)
                    if (pen.offset != 1) size *= 0.65f
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        typeface = typeface(pen)
                        textSize = size.coerceAtLeast(4f)
                        isUnderlineText = pen.underline
                    }
                    val fm = paint.fontMetrics
                    val rw = paint.measureText(part)
                    runs.add(Run(part, pen, paint, rw))
                    lineW += rw
                    asc = maxOf(asc, -fm.ascent)
                    desc = maxOf(desc, fm.descent)
                }
            }
            endLine()
            while (lines.size > 1 && lines.last().runs.isEmpty()) lines.removeAt(lines.size - 1)

            val padX = base * 0.25f
            val blockW = (lines.maxOfOrNull { it.width } ?: 0f) + 2 * padX
            val blockH = lines.sumOf { (it.ascent + it.descent).toDouble() }.toFloat()
            // position: YouTube keeps a 2% margin, positions run over the other 96%
            val px = w * (0.02f + ev.pos.h * 0.0096f)
            // bottom-anchored captions sit a little lower on YouTube than a strict 2% margin
            val py = h * (0.02f + ev.pos.v * 0.0096f) +
                (if (ev.pos.anchor / 3 == 2) h * 0.01f else 0f)
            val left = when (ev.pos.anchor % 3) {
                0 -> px
                1 -> px - blockW / 2
                else -> px - blockW
            }.coerceIn(0f, maxOf(0f, w - blockW))
            val top = when (ev.pos.anchor / 3) {
                0 -> py
                1 -> py - blockH / 2
                else -> py - blockH
            }.coerceIn(0f, maxOf(0f, h - blockH))
            // (the clamp keeps the block on the picture)

            var y = top
            for (line in lines) {
                val x0 = left + padX + when (ev.style.justify) {
                    0 -> 0f
                    1 -> (blockW - 2 * padX) - line.width
                    else -> ((blockW - 2 * padX) - line.width) / 2
                }
                val baseline = y + line.ascent
                // backgrounds first, per run, so differently coloured runs keep their own box
                var x = x0
                for ((ri, r) in line.runs.withIndex()) {
                    if (r.pen.bgAlpha > 0) {
                        bgPaint.color = (r.pen.bgAlpha shl 24) or r.pen.bg
                        val l = if (ri == 0) x - padX else x
                        val rgt = if (ri == line.runs.size - 1) x + r.width + padX else x + r.width
                        canvas.drawRect(RectF(l, y, rgt, y + line.ascent + line.descent), bgPaint)
                    }
                    x += r.width
                }
                x = x0
                for (r in line.runs) {
                    val shift = when (r.pen.offset) {
                        0 -> line.descent * 0.6f
                        2 -> -line.ascent * 0.45f
                        else -> 0f
                    }
                    drawRun(canvas, r, x, baseline + shift)
                    x += r.width
                }
                y += line.ascent + line.descent
            }
        }
    }

    private fun drawRun(canvas: Canvas, r: Run, x: Float, y: Float) {
        val pen = r.pen
        val p = r.paint
        val edge = 0xFF000000.toInt() or pen.edgeColor
        val fgAlpha = pen.fgAlpha.coerceIn(0, 255)
        val k = maxOf(1f, p.textSize / 18f)
        when (pen.edgeType) {
            1 -> { // hard shadow
                p.color = edge; p.alpha = fgAlpha
                canvas.drawText(r.text, x + k, y + k, p)
            }
            2 -> { // bevel: light above-left, dark below-right
                p.color = edge; p.alpha = fgAlpha
                canvas.drawText(r.text, x + k * 0.7f, y + k * 0.7f, p)
                p.color = Color.WHITE; p.alpha = fgAlpha / 2
                canvas.drawText(r.text, x - k * 0.5f, y - k * 0.5f, p)
            }
            3 -> { // glow / outline
                p.style = Paint.Style.STROKE
                p.strokeWidth = p.textSize * 0.12f
                p.strokeJoin = Paint.Join.ROUND
                p.color = edge; p.alpha = fgAlpha
                canvas.drawText(r.text, x, y, p)
                p.style = Paint.Style.FILL
            }
            4 -> { // soft shadow
                p.color = edge; p.alpha = fgAlpha
                p.maskFilter = BlurMaskFilter(k * 1.5f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawText(r.text, x + k, y + k, p)
                p.maskFilter = null
            }
        }
        p.color = 0xFF000000.toInt() or pen.fg
        p.alpha = fgAlpha
        canvas.drawText(r.text, x, y, p)
    }
}
