package org.honeycomb.launcher

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs

/**
 * Minimal, distraction-free iOS-style Android keyboard bundled inside Honeycomb:
 * zero suggestion strip, zero toolbar clutter, zero internet permission, and crisp native-View touch latency.
 */
object HoneycombKeyboard {
    fun isEnabled(ctx: Context): Boolean {
        val imm = ctx.getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { it.packageName == ctx.packageName }
    }

    /** If enabled, opens Android's 1-tap keyboard picker; otherwise opens On-Screen Keyboard settings to toggle it on. */
    fun openSettingsOrPicker(ctx: Context) {
        val imm = ctx.getSystemService(InputMethodManager::class.java)
        if (isEnabled(ctx) && imm != null) {
            imm.showInputMethodPicker()
        } else {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

internal enum class KeyboardPage { LETTERS, NUMBERS, SYMBOLS }
internal enum class ShiftMode { OFF, ONCE, LOCKED }

internal sealed interface KeyKind {
    data class CharKey(val value: String) : KeyKind
    data object Shift : KeyKind
    data object Backspace : KeyKind
    data class SwitchPage(val target: KeyboardPage, val label: String) : KeyKind
    data object Space : KeyKind
    data object Action : KeyKind
}

internal data class KeySpec(
    val kind: KeyKind,
    val weight: Float = 1f,
    val isModifier: Boolean = false,
)

internal object KeyboardLayout {
    fun rows(page: KeyboardPage): List<List<KeySpec>> = when (page) {
        KeyboardPage.LETTERS -> listOf(
            "qwertyuiop".map { KeySpec(KeyKind.CharKey(it.toString())) },
            "asdfghjkl".map { KeySpec(KeyKind.CharKey(it.toString())) },
            listOf(KeySpec(KeyKind.Shift, 1.45f, isModifier = true)) +
                "zxcvbnm".map { KeySpec(KeyKind.CharKey(it.toString())) } +
                listOf(KeySpec(KeyKind.Backspace, 1.45f, isModifier = true)),
            listOf(
                KeySpec(KeyKind.SwitchPage(KeyboardPage.NUMBERS, "123"), 1.9f, isModifier = true),
                KeySpec(KeyKind.Space, 5.7f, isModifier = false),
                KeySpec(KeyKind.Action, 2.4f, isModifier = true),
            ),
        )
        KeyboardPage.NUMBERS -> listOf(
            "1234567890".map { KeySpec(KeyKind.CharKey(it.toString())) },
            listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\"").map { KeySpec(KeyKind.CharKey(it)) },
            listOf(KeySpec(KeyKind.SwitchPage(KeyboardPage.SYMBOLS, "#+="), 1.45f, isModifier = true)) +
                listOf(".", ",", "?", "!", "'").map { KeySpec(KeyKind.CharKey(it), 1.42f) } +
                listOf(KeySpec(KeyKind.Backspace, 1.45f, isModifier = true)),
            listOf(
                KeySpec(KeyKind.SwitchPage(KeyboardPage.LETTERS, "ABC"), 1.9f, isModifier = true),
                KeySpec(KeyKind.Space, 5.7f, isModifier = false),
                KeySpec(KeyKind.Action, 2.4f, isModifier = true),
            ),
        )
        KeyboardPage.SYMBOLS -> listOf(
            listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=").map { KeySpec(KeyKind.CharKey(it)) },
            listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•").map { KeySpec(KeyKind.CharKey(it)) },
            listOf(KeySpec(KeyKind.SwitchPage(KeyboardPage.NUMBERS, "123"), 1.45f, isModifier = true)) +
                listOf(".", ",", "?", "!", "'").map { KeySpec(KeyKind.CharKey(it), 1.42f) } +
                listOf(KeySpec(KeyKind.Backspace, 1.45f, isModifier = true)),
            listOf(
                KeySpec(KeyKind.SwitchPage(KeyboardPage.LETTERS, "ABC"), 1.9f, isModifier = true),
                KeySpec(KeyKind.Space, 5.7f, isModifier = false),
                KeySpec(KeyKind.Action, 2.4f, isModifier = true),
            ),
        )
    }

    fun actionLabel(imeOptions: Int): String {
        if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "return"
        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "search"
            EditorInfo.IME_ACTION_GO -> "go"
            EditorInfo.IME_ACTION_SEND -> "send"
            EditorInfo.IME_ACTION_DONE -> "done"
            EditorInfo.IME_ACTION_NEXT -> "next"
            else -> "return"
        }
    }

    fun isProminentAction(imeOptions: Int): Boolean {
        if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return false
        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE -> true
            else -> false
        }
    }

    fun shouldDoubleSpacePeriod(beforeCursor: CharSequence?): Boolean {
        if (beforeCursor == null || beforeCursor.length < 2) return false
        val last = beforeCursor[beforeCursor.length - 1]
        val prev = beforeCursor[beforeCursor.length - 2]
        return last == ' ' && prev.isLetterOrDigit()
    }

    fun nextShiftOnTap(current: ShiftMode, doubleTap: Boolean): ShiftMode = when {
        current == ShiftMode.LOCKED -> ShiftMode.OFF
        doubleTap -> ShiftMode.LOCKED
        current == ShiftMode.ONCE -> ShiftMode.OFF
        else -> ShiftMode.ONCE
    }
}

class HoneycombKeyboardService : InputMethodService() {
    private var keyboardView: MinimalKeyboardView? = null

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val v = MinimalKeyboardView(this).apply {
            onCommitText = { text -> currentInputConnection?.commitText(text, 1) }
            onBackspace = { deleteOneChar() }
            onDoubleSpacePeriod = { replaceTrailingSpaceWithPeriod() }
            onAction = { performImeAction() }
            onMoveCursor = { delta -> moveCursorBy(delta) }
            queryAutoShift = { shouldAutoShift() }
            queryBeforeCursor = { currentInputConnection?.getTextBeforeCursor(2, 0) }
        }
        keyboardView = v
        currentInputEditorInfo?.let { v.bindEditor(it) }
        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        info?.let { keyboardView?.bindEditor(it) }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        keyboardView?.syncAutoShift()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        keyboardView?.refreshTheme()
    }

    private fun deleteOneChar() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingTextInCodePoints(1, 0)
        }
    }

    private fun replaceTrailingSpaceWithPeriod() {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(1, 0)
        ic.commitText(". ", 1)
    }

    private fun moveCursorBy(delta: Int) {
        val ic = currentInputConnection ?: return
        val key = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(delta)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        }
    }

    private fun performImeAction() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val opts = info?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnter = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnter && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            if (ic.performEditorAction(action)) return
        }
        ic.commitText("\n", 1)
    }

    private fun shouldAutoShift(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val ic: InputConnection = currentInputConnection ?: return false
        val inputType = info.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        ) return false
        return ic.getCursorCapsMode(inputType) != 0
    }
}

/**
 * Pure Canvas-rendered iOS-minimal keyboard view: sub-millisecond input latency, zero suggestion bar,
 * zero toolbar clutter, subtle key loupe, hold-to-repeat backspace, and spacebar trackpad cursor sliding.
 */
private class MinimalKeyboardView(context: Context) : View(context) {
    var onCommitText: (String) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onDoubleSpacePeriod: () -> Unit = {}
    var onAction: () -> Unit = {}
    var onMoveCursor: (Int) -> Unit = {}
    var queryAutoShift: () -> Boolean = { false }
    var queryBeforeCursor: () -> CharSequence? = { null }

    private data class PlacedKey(val spec: KeySpec, val rect: RectF, val row: Int)

    private var page = KeyboardPage.LETTERS
    private var shift = ShiftMode.OFF
    private var actionLabel = "return"
    private var prominentAction = false
    private var navBottomInsetPx = 0

    private val placed = ArrayList<PlacedKey>(40)
    private var activeKey: PlacedKey? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private var lastShiftTapMs = 0L
    private var lastSpaceTapMs = 0L
    private var spaceTrackpadActive = false
    private var spaceDownX = 0f
    private var spaceLastStepX = 0f

    private val handler = Handler(Looper.getMainLooper())
    private var repeatingBackspace = false
    private val backspaceRepeater = object : Runnable {
        override fun run() {
            if (!repeatingBackspace) return
            onBackspace()
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            handler.postDelayed(this, 55L)
        }
    }

    private val dp = resources.displayMetrics.density
    private val keyHeightPx = 45f * dp
    private val rowGapPx = 11f * dp
    private val keyGapPx = 6f * dp
    private val sidePadPx = 4f * dp
    private val topPadPx = 12f * dp
    private val bottomPadPx = 10f * dp
    private val cornerPx = 7f * dp
    private val shadowOffsetPx = 1f * dp

    // Palette (refreshed on light/dark theme switch)
    private var deckColor = 0
    private var keyFill = 0
    private var keyPressedFill = 0
    private var modFill = 0
    private var modPressedFill = 0
    private var shiftActiveFill = 0
    private var shiftActiveInk = 0
    private var accentFill = 0
    private var accentPressedFill = 0
    private var shadowColor = 0
    private var inkColor = 0
    private var subtleInkColor = 0
    private var accentInkColor = 0

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.9f * dp
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tempRect = RectF()
    private val tempPath = Path()

    init {
        isHapticFeedbackEnabled = true
        refreshTheme()
        setOnApplyWindowInsetsListener { _, insets ->
            navBottomInsetPx = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            requestLayout()
            insets
        }
    }

    fun refreshTheme() {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (dark) {
            deckColor = Color.rgb(28, 28, 30)
            keyFill = Color.rgb(74, 74, 77)
            keyPressedFill = Color.rgb(106, 106, 110)
            modFill = Color.rgb(46, 46, 49)
            modPressedFill = Color.rgb(74, 74, 77)
            shiftActiveFill = Color.rgb(242, 242, 247)
            shiftActiveInk = Color.rgb(28, 28, 30)
            accentFill = Color.rgb(10, 132, 255)
            accentPressedFill = Color.rgb(64, 156, 255)
            shadowColor = Color.rgb(12, 12, 14)
            inkColor = Color.rgb(255, 255, 255)
            subtleInkColor = Color.rgb(174, 174, 178)
            accentInkColor = Color.rgb(255, 255, 255)
        } else {
            deckColor = Color.rgb(209, 213, 219)
            keyFill = Color.rgb(255, 255, 255)
            keyPressedFill = Color.rgb(229, 231, 235)
            modFill = Color.rgb(172, 179, 191)
            modPressedFill = Color.rgb(255, 255, 255)
            shiftActiveFill = Color.rgb(255, 255, 255)
            shiftActiveInk = Color.rgb(17, 24, 39)
            accentFill = Color.rgb(0, 122, 255)
            accentPressedFill = Color.rgb(0, 98, 214)
            shadowColor = Color.rgb(137, 142, 150)
            inkColor = Color.rgb(17, 24, 39)
            subtleInkColor = Color.rgb(75, 85, 99)
            accentInkColor = Color.rgb(255, 255, 255)
        }
        invalidate()
    }

    fun bindEditor(info: EditorInfo) {
        actionLabel = KeyboardLayout.actionLabel(info.imeOptions)
        prominentAction = KeyboardLayout.isProminentAction(info.imeOptions)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        page = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) {
            KeyboardPage.NUMBERS
        } else {
            KeyboardPage.LETTERS
        }
        shift = if (page == KeyboardPage.LETTERS && queryAutoShift()) ShiftMode.ONCE else ShiftMode.OFF
        rebuildKeys(width)
        invalidate()
    }

    fun syncAutoShift() {
        if (page != KeyboardPage.LETTERS || shift == ShiftMode.LOCKED) return
        val want = if (queryAutoShift()) ShiftMode.ONCE else ShiftMode.OFF
        if (want != shift) {
            shift = want
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val totalH = (topPadPx + 4 * keyHeightPx + 3 * rowGapPx + bottomPadPx + navBottomInsetPx).toInt()
        setMeasuredDimension(w, totalH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildKeys(w)
    }

    private fun rebuildKeys(viewWidth: Int) {
        placed.clear()
        if (viewWidth <= 0) return
        val rows = KeyboardLayout.rows(page)
        val usableW = viewWidth - 2f * sidePadPx
        rows.forEachIndexed { rIdx, row ->
            val top = topPadPx + rIdx * (keyHeightPx + rowGapPx)
            val bottom = top + keyHeightPx
            val isIndentedAlpha = page == KeyboardPage.LETTERS && rIdx == 1
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            val rowUsableW = if (isIndentedAlpha) usableW * 0.90f else usableW
            val startX = sidePadPx + (usableW - rowUsableW) / 2f
            val totalGaps = (row.size - 1) * keyGapPx
            val unitW = (rowUsableW - totalGaps) / totalWeight
            var x = startX
            for (spec in row) {
                val kw = spec.weight * unitW
                placed.add(PlacedKey(spec, RectF(x, top, x + kw, bottom), rIdx))
                x += kw + keyGapPx
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(deckColor)
        shadowPaint.color = shadowColor
        textPaint.textSize = 22f * dp
        smallTextPaint.textSize = 15.5f * dp

        for (k in placed) {
            val pressed = k == activeKey && !spaceTrackpadActive
            drawKey(canvas, k, pressed)
        }
        // Clean iOS key loupe above active character key
        val active = activeKey
        if (active != null && active.spec.kind is KeyKind.CharKey && !spaceTrackpadActive) {
            drawLoupe(canvas, active)
        }
    }

    private fun drawKey(canvas: Canvas, k: PlacedKey, pressed: Boolean) {
        val r = k.rect
        val isShiftOn = k.spec.kind == KeyKind.Shift && shift != ShiftMode.OFF
        val isAction = k.spec.kind == KeyKind.Action && prominentAction
        val fill = when {
            isAction -> if (pressed) accentPressedFill else accentFill
            isShiftOn -> shiftActiveFill
            k.spec.isModifier -> if (pressed) modPressedFill else modFill
            else -> if (pressed) keyPressedFill else keyFill
        }
        // Subtle 1dp iOS bottom shadow
        tempRect.set(r.left, r.top + shadowOffsetPx, r.right, r.bottom + shadowOffsetPx)
        canvas.drawRoundRect(tempRect, cornerPx, cornerPx, shadowPaint)
        fillPaint.color = fill
        canvas.drawRoundRect(r, cornerPx, cornerPx, fillPaint)

        val cx = r.centerX()
        val cy = r.centerY()
        when (val kind = k.spec.kind) {
            is KeyKind.CharKey -> {
                val ch = displayChar(kind.value)
                textPaint.color = inkColor
                textPaint.textSize = if (page == KeyboardPage.LETTERS && shift == ShiftMode.OFF) 22.5f * dp else 21f * dp
                val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(ch, cx, baseline, textPaint)
            }
            KeyKind.Shift -> drawShiftGlyph(canvas, cx, cy, if (isShiftOn) shiftActiveInk else inkColor, shift)
            KeyKind.Backspace -> drawBackspaceGlyph(canvas, cx, cy, inkColor)
            is KeyKind.SwitchPage -> {
                smallTextPaint.color = inkColor
                val baseline = cy - (smallTextPaint.descent() + smallTextPaint.ascent()) / 2f
                canvas.drawText(kind.label, cx, baseline, smallTextPaint)
            }
            KeyKind.Space -> {
                smallTextPaint.color = subtleInkColor
                val baseline = cy - (smallTextPaint.descent() + smallTextPaint.ascent()) / 2f
                canvas.drawText("space", cx, baseline, smallTextPaint)
            }
            KeyKind.Action -> {
                smallTextPaint.color = if (prominentAction) accentInkColor else inkColor
                val baseline = cy - (smallTextPaint.descent() + smallTextPaint.ascent()) / 2f
                canvas.drawText(actionLabel, cx, baseline, smallTextPaint)
            }
        }
    }

    private fun drawLoupe(canvas: Canvas, k: PlacedKey) {
        val kind = k.spec.kind as? KeyKind.CharKey ?: return
        val r = k.rect
        val padX = 5f * dp
        val lift = (r.height() + 8f * dp).coerceAtMost(r.top - 2f * dp)
        if (lift < 8f * dp) return
        val top = (r.top - lift).coerceAtLeast(2f * dp)
        tempRect.set(
            (r.left - padX).coerceAtLeast(2f * dp),
            top,
            (r.right + padX).coerceAtMost(width - 2f * dp),
            r.top + 4f * dp,
        )
        shadowPaint.color = Color.argb(65, 0, 0, 0)
        canvas.drawRoundRect(
            tempRect.left,
            tempRect.top + 2f * dp,
            tempRect.right,
            tempRect.bottom + 2f * dp,
            cornerPx * 1.3f,
            cornerPx * 1.3f,
            shadowPaint,
        )
        fillPaint.color = keyFill
        canvas.drawRoundRect(tempRect, cornerPx * 1.3f, cornerPx * 1.3f, fillPaint)
        textPaint.color = inkColor
        textPaint.textSize = 28f * dp
        val baseline = tempRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(displayChar(kind.value), tempRect.centerX(), baseline, textPaint)
    }

    private fun drawShiftGlyph(canvas: Canvas, cx: Float, cy: Float, color: Int, mode: ShiftMode) {
        val s = 8.5f * dp
        val topY = cy - s
        val midY = cy + 0.5f * dp
        val botY = cy + s * 0.85f
        tempPath.reset()
        tempPath.moveTo(cx, topY)
        tempPath.lineTo(cx + s, midY)
        tempPath.lineTo(cx + s * 0.45f, midY)
        tempPath.lineTo(cx + s * 0.45f, botY)
        tempPath.lineTo(cx - s * 0.45f, botY)
        tempPath.lineTo(cx - s * 0.45f, midY)
        tempPath.lineTo(cx - s, midY)
        tempPath.close()
        if (mode != ShiftMode.OFF) {
            iconFillPaint.color = color
            canvas.drawPath(tempPath, iconFillPaint)
        } else {
            iconPaint.color = color
            canvas.drawPath(tempPath, iconPaint)
        }
        if (mode == ShiftMode.LOCKED) {
            iconPaint.color = color
            canvas.drawLine(cx - s * 0.5f, botY + 3.5f * dp, cx + s * 0.5f, botY + 3.5f * dp, iconPaint)
        }
    }

    private fun drawBackspaceGlyph(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val w = 10.5f * dp
        val h = 7.2f * dp
        tempPath.reset()
        tempPath.moveTo(cx - w, cy)
        tempPath.lineTo(cx - w * 0.35f, cy - h)
        tempPath.lineTo(cx + w, cy - h)
        tempPath.lineTo(cx + w, cy + h)
        tempPath.lineTo(cx - w * 0.35f, cy + h)
        tempPath.close()
        iconPaint.color = color
        canvas.drawPath(tempPath, iconPaint)
        val xSize = 3.2f * dp
        val xCx = cx + 2.2f * dp
        canvas.drawLine(xCx - xSize, cy - xSize, xCx + xSize, cy + xSize, iconPaint)
        canvas.drawLine(xCx + xSize, cy - xSize, xCx - xSize, cy + xSize, iconPaint)
    }

    private fun displayChar(raw: String): String =
        if (page == KeyboardPage.LETTERS && shift != ShiftMode.OFF) raw.uppercase() else raw

    private fun hitKey(x: Float, y: Float): PlacedKey? {
        // Exact hit first, then nearest key within the tapped row so edge taps never miss.
        placed.firstOrNull { it.rect.contains(x, y) }?.let { return it }
        val row = ((y - topPadPx) / (keyHeightPx + rowGapPx)).toInt().coerceIn(0, 3)
        return placed.filter { it.row == row }.minByOrNull { abs(it.rect.centerX() - x) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                if (activeKey != null) {
                    // Rapid two-thumb typing: commit previous key immediately before starting the new one.
                    finishActiveKey(commit = true)
                }
                activePointerId = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                val k = hitKey(x, y)
                activeKey = k
                spaceTrackpadActive = false
                if (k != null) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (k.spec.kind == KeyKind.Space) {
                        spaceDownX = x
                        spaceLastStepX = x
                    } else if (k.spec.kind == KeyKind.Backspace) {
                        onBackspace()
                        repeatingBackspace = true
                        handler.removeCallbacks(backspaceRepeater)
                        handler.postDelayed(backspaceRepeater, 380L)
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return true
                val x = event.getX(idx)
                val y = event.getY(idx)
                if (activeKey?.spec?.kind == KeyKind.Space) {
                    val stepPx = 15f * dp
                    if (!spaceTrackpadActive && abs(x - spaceDownX) > 18f * dp) {
                        spaceTrackpadActive = true
                        spaceLastStepX = x
                    }
                    if (spaceTrackpadActive) {
                        val delta = ((x - spaceLastStepX) / stepPx).toInt()
                        if (delta != 0) {
                            onMoveCursor(delta)
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            spaceLastStepX += delta * stepPx
                        }
                    }
                    return true
                }
                if (activeKey?.spec?.kind is KeyKind.CharKey) {
                    val over = hitKey(x, y)
                    if (over != null && over.spec.kind is KeyKind.CharKey && over != activeKey) {
                        activeKey = over
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                if (event.getPointerId(idx) == activePointerId) {
                    finishActiveKey(commit = !spaceTrackpadActive)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                finishActiveKey(commit = false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun finishActiveKey(commit: Boolean) {
        val k = activeKey
        activeKey = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
        repeatingBackspace = false
        handler.removeCallbacks(backspaceRepeater)
        val wasTrackpad = spaceTrackpadActive
        spaceTrackpadActive = false
        if (commit && !wasTrackpad && k != null) {
            handleKeyCommit(k.spec.kind)
        }
        invalidate()
    }

    private fun handleKeyCommit(kind: KeyKind) {
        val now = SystemClock.uptimeMillis()
        when (kind) {
            is KeyKind.CharKey -> {
                onCommitText(displayChar(kind.value))
                if (page == KeyboardPage.LETTERS && shift == ShiftMode.ONCE) {
                    shift = ShiftMode.OFF
                }
            }
            KeyKind.Shift -> {
                val doubleTap = now - lastShiftTapMs < 320L
                shift = KeyboardLayout.nextShiftOnTap(shift, doubleTap)
                lastShiftTapMs = if (shift == ShiftMode.LOCKED) 0L else now
            }
            KeyKind.Backspace -> {
                // Already deleted on ACTION_DOWN; sync auto-shift after deletion.
                syncAutoShift()
            }
            is KeyKind.SwitchPage -> {
                page = kind.target
                if (page == KeyboardPage.LETTERS) {
                    shift = if (queryAutoShift()) ShiftMode.ONCE else ShiftMode.OFF
                }
                rebuildKeys(width)
            }
            KeyKind.Space -> {
                if (now - lastSpaceTapMs < 320L && KeyboardLayout.shouldDoubleSpacePeriod(queryBeforeCursor())) {
                    onDoubleSpacePeriod()
                    lastSpaceTapMs = 0L
                } else {
                    onCommitText(" ")
                    lastSpaceTapMs = now
                }
                if (page != KeyboardPage.LETTERS) {
                    page = KeyboardPage.LETTERS
                    rebuildKeys(width)
                }
                syncAutoShift()
            }
            KeyKind.Action -> {
                onAction()
            }
        }
    }
}
