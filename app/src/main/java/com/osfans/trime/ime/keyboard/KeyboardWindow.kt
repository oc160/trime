// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.graphics.Point
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.bar.ui.PinyinBarUi
import com.osfans.trime.ime.bar.ui.PinyinSyllables
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.ResidentWindow
import com.osfans.trime.util.isLandscape
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.systemservices.windowManager
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

// 空闲态左侧栏：成对符号点一次出一对（光标居中），其余单符号直接上屏
// payload: "ins|<文本>|<光标左移格数>"
private val IDLE_SYMBOLS =
    listOf(
        "（）" to "ins|（）|1",
        "“”" to "ins|“”|1",
        "【】" to "ins|【】|1",
        "%" to "ins|%|0",
        "@" to "ins|@|0",
        "；" to "ins|；|0",
        "：" to "ins|：|0",
        "……" to "ins|……|0",
    )

private const val MAX_STRIP_ENTRIES = 40

private const val PINYIN_APOSTROPHE = '\u0027'

class KeyboardWindow(di: DI) :
    BoardWindow.NoBarBoardWindow(di),
    ResidentWindow,
    InputBroadcastReceiver {
    private val service: TrimeInputMethodService by instance()
    private val theme: Theme by instance()
    private val scope: ThemeScope by instance()
    private val rime: RimeSession by instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by instance()
    private val popup: PopupDelegate by instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by instance()

    private val cursorCapsMode: Int
        get() =
            service.currentInputEditorInfo.run {
                if (inputType != InputType.TYPE_NULL) {
                    service.currentInputConnection?.getCursorCapsMode(inputType) ?: 0
                } else {
                    0
                }
            }

    private val _currentKeyboardHeight =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val currentKeyboardHeight = _currentKeyboardHeight.asSharedFlow()

    private lateinit var keyboardView: FrameLayout

    private val pinyinPattern = Regex("^[a-z]+( [a-z]+)*$")

    private var stripIsPunctuation = false

    private var stripLeft = 0
    private var stripTop = 0
    private var stripWidth = 0
    private var stripHeight = 0

    /** 九键左侧拼音滚动栏：叠在三行数字键左侧的留白列上（Gboard 同款）。 */
    private val pinyinBar by lazy {
        PinyinBarUi(context, scope).apply {
            onChipSelected = { payload ->
                if (stripIsPunctuation) {
                    handleSymbolPayload(payload)
                } else if (payload.startsWith("syl|")) {
                    val rebuild = payload.substringAfter("syl|")
                    rime.launchOnReady {
                        it.clearComposition()
                        it.simulateKeySequence(rebuild)
                    }
                }
            }
            visibility = View.GONE
        }
    }

    companion object : ResidentWindow.Key {
        lateinit var currentKeyboard: Keyboard
    }

    override val key: ResidentWindow.Key
        get() = KeyboardWindow

    private val presetKeyboardIds = theme.presetKeyboards.keys.toList()
    private var currentKeyboardId = ""
    private var lastKeyboardId = ""
    private var lastLockKeyboardId = ""
    private var tempAsciiMode: Boolean? = null
    private val cachedKeyboards = mutableMapOf<String, Pair<Keyboard, KeyboardView>>()
    private val activeKeyboard: Keyboard? get() = cachedKeyboards[currentKeyboardId]?.first
    private val currentKeyboardView: KeyboardView? get() = cachedKeyboards[currentKeyboardId]?.second

    private val keyboardActionListener = commonKeyboardActionListener.listener

    private var lastIsPortrait: Boolean? = null
    private var containerWidth: Int = 0
    private var allowedWidth: Int = 0

    private val onKeyboardViewLayoutChangeListener =
        View.OnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && allowedWidth != width) {
                val isPortrait = !context.resources.configuration.isLandscape()
                lastIsPortrait = isPortrait
                containerWidth = width
                allowedWidth = width
                v.post { refreshKeyboards() }
            }
        }

    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        keyboardView.addOnLayoutChangeListener(onKeyboardViewLayoutChangeListener)
        attachKeyboard(evalKeyboard(".default"))
        return keyboardView
    }

    private fun detachCurrentView() {
        currentKeyboardView?.also {
            it.onDetach()
            keyboardView.removeView(it)
        }
        activeKeyboard?.lastAsciiMode = rime.run { statusCached }.isAsciiMode
    }

    /** 计算键盘可用宽度：优先使用已测量的容器宽度，否则回退到系统窗口测量。 */
    private fun computeAllowedWidth(): Int {
        val isPortrait = !context.resources.configuration.isLandscape()

        if (containerWidth > 0 && lastIsPortrait == isPortrait) {
            return containerWidth
        }

        val padding = theme.style.run {
            if (context.isLandscapeMode()) keyboardPaddingLand else keyboardPadding
        }

        val safeWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = context.windowManager.maximumWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val displayWidth = context.resources.displayMetrics.widthPixels
            val windowWidth = windowMetrics.bounds.width() - insets.left - insets.right
            if (windowWidth < displayWidth - context.dp(1)) displayWidth else windowWidth
        } else {
            @Suppress("DEPRECATION")
            val size = Point()
            @Suppress("DEPRECATION")
            context.windowManager.defaultDisplay.getSize(size)
            size.x
        }

        val width = safeWidth - 2 * context.dp(padding)
        allowedWidth = width
        return width
    }

    private fun selectKeyboardConfig(name: String): TextKeyboard? {
        val config = theme.presetKeyboards[name] ?: theme.presetKeyboards["default"]
        val importPreset = config?.importPreset
        if (!importPreset.isNullOrEmpty()) {
            return selectKeyboardConfig(importPreset)
        }
        return config
    }

    private fun attachKeyboard(target: String) {
        currentKeyboardId = target
        lastKeyboardId = target

        val config = selectKeyboardConfig(target)
        val keyboard = activeKeyboard ?: Keyboard(context, theme, computeAllowedWidth(), config)
        val view = currentKeyboardView ?: KeyboardView(context, theme, keyboard, popup, service, keyboardActionListener, enterKeyDisplay)

        if (activeKeyboard == null) {
            cachedKeyboards[target] = keyboard to view
            keyboard.lastAsciiMode = keyboard.asciiMode
        }

        keyboard.also {
            runBlocking { _currentKeyboardHeight.emit(it.keyboardHeight) }
            if (it.isLock) lastLockKeyboardId = target
            dispatchCapsState(it::setShifted)

            val currentMode = rime.run { statusCached }.isAsciiMode
            val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode

            if (currentMode != targetMode) {
                service.postRimeJob {
                    commitComposition()
                    setRuntimeOption("ascii_mode", targetMode)
                }
            }

            currentKeyboard = it
        }

        view.let {
            Timber.i("KeyboardWindows: attach %s", target)
            it.setOnTouchListener { _, event -> handlePinyinStripTouch(event) }
            keyboardView.apply {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                add(it, lParams(matchParent, matchParent))
            }
        }
        runCatching { layoutPinyinBar() }
            .onFailure { Timber.e(it, "layoutPinyinBar failed") }
        Timber.i("KeyboardWindows: attach done %s strip=%dx%d", target, stripWidth, stripHeight)
    }

    /**
     * 把拼音栏定位到主题在三行数字键左侧留出的空白列上（Gboard 布局）。
     * 留白键不生成 Key 对象，故用首行真实键的 x 推留白宽度，
     * 用末行（row=3）键的 y 作侧栏底边。
     */
    private fun layoutPinyinBar() {
        val keys = currentKeyboard.keys
        val topRows = keys.filter { it.row <= 2 }
        val bottomRowKeys = keys.filter { it.row == 3 }
        if (topRows.isEmpty() || bottomRowKeys.isEmpty()) {
            pinyinBar.visibility = View.GONE
            return
        }
        val stripWidthInPx = topRows.minOf { it.x }
        val top = topRows.minOf { it.y }
        val bottom = bottomRowKeys.minOf { it.y }
        if (stripWidthInPx <= 0 || bottom <= top) {
            pinyinBar.visibility = View.GONE
            return
        }
        stripLeft = keyboardView.paddingLeft
        stripTop = keyboardView.paddingTop + top
        stripWidth = stripWidthInPx
        stripHeight = bottom - top
        if (pinyinBar.parent == null) {
            keyboardView.addView(pinyinBar)
        }
        pinyinBar.layoutParams =
            FrameLayout.LayoutParams(stripWidthInPx, bottom - top).apply {
                leftMargin = stripLeft
                topMargin = stripTop
            }
        pinyinBar.bringToFront()
        pinyinBar.requestLayout()
    }

    /**
     * 键区触摸：先看是否落在左侧拼音栏上（悬浮视图收不到点击，统一在此处理），
     * 命中则锁定读音；未命中交给按键分发。
     */
    private fun handlePinyinStripTouch(event: MotionEvent): Boolean {
        if (pinyinBar.visibility != View.VISIBLE) return false
        val localX = event.x - stripLeft
        val localY = event.y - stripTop
        if (localX < 0 || localX > stripWidth || localY < 0 || localY > stripHeight) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> return true

            MotionEvent.ACTION_UP -> {
                pinyinBar.hitTest(localX, localY)?.let { pinyin ->
                    Timber.i("PinyinBar: strip tap %s", pinyin)
                    pinyinBar.onChipSelected?.invoke(pinyin)
                }
                return true
            }
        }
        return false
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        if (!AppPrefs.defaultInstance().general.pinyinSelectionBar.getValue()) {
            pinyinBar.update(emptyList())
            return
        }
        if (data.candidates.isEmpty()) {
            showIdlePunctuation()
            return
        }
        stripIsPunctuation = false
        // 数字驱动的候选拼音：按当前原始输入枚举所有可能音节（含 zh/x 等前缀）
        rime.launchOnReady {
            val raw = runCatching { it.getRawInput() }.getOrDefault("")
            keyboardView.post { renderDigitStrip(raw) }
        }
    }

    /** 空闲态：左侧栏显示符号（成对符号自动补另一半），点按上屏。 */
    private fun showIdlePunctuation() {
        stripIsPunctuation = true
        pinyinBar.update(IDLE_SYMBOLS)
    }

    /**
     * 从原始输入找第一个"纯数字"段，枚举该段可形成的所有音节：
     * 每个音节消耗其长度的数字，剩余输入原样保留（如 9426 点 xi → xi'26）。
     */
    private fun renderDigitStrip(raw: String) {
        if (stripIsPunctuation) return
        var segStart = 0
        var digits: String? = null
        for (seg in raw.split('\'')) {
            if (seg.isNotEmpty() && seg.all { it.isDigit() }) {
                digits = seg
                break
            }
            segStart += seg.length + 1
        }
        if (digits == null) {
            pinyinBar.update(emptyList())
            return
        }
        Timber.i("StripDigit: raw=[%s] segStart=%d digits=[%s]", raw, segStart, digits)
        val syllables = PinyinSyllables.candidatesFor(digits).take(MAX_STRIP_ENTRIES)
        if (syllables.isEmpty()) {
            pinyinBar.update(emptyList())
            return
        }
        val entries =
            syllables.map { syl ->
                val consumed = syl.length
                val rest = raw.substring((segStart + consumed).coerceAtMost(raw.length))
                val rebuild = raw.substring(0, segStart) + syl + "'" + rest
                syl to "syl|$rebuild"
            }
        pinyinBar.update(entries)
    }

    /** 空闲态符号：成对符号一次上屏一对并把光标移到中间。 */
    private fun handleSymbolPayload(payload: String) {
        if (!payload.startsWith("ins|")) return
        val parts = payload.split('|')
        val text = parts.getOrNull(1) ?: return
        val back = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val ic = service.currentInputConnection ?: return
        ic.commitText(text, 1)
        repeat(back) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        }
    }

    private fun smartMatchKeyboard(): String {
        // 主题的布局中包含方案id，直接采用
        val currentSchema = rime.run { statusCached }.schemaId
        if (presetKeyboardIds.contains(currentSchema)) {
            return currentSchema
        }
        val alphabet = rime.run { schemaCached }.alphabet
        val layout =
            when {
                alphabet.all { it.isLetter() } -> "qwerty"

                // 包含 26 个字母
                alphabet.all { it.isLetter() || ",./;".any(it::equals) } -> "qwerty_"

                // 包含 26 个字母和,./;
                alphabet.all { it.isLetterOrDigit() } -> "qwerty0"

                // 包含 26 个字母和数字键
                else -> "default"
            }
        return if (presetKeyboardIds.contains(layout)) layout else "default"
    }

    private fun evalKeyboard(id: String): String {
        val currentIdx = presetKeyboardIds.indexOfFirst { currentKeyboardId == it }
        val dot =
            when (id) {
                ".default" -> smartMatchKeyboard()

                ".prior" -> presetKeyboardIds.getOrNull(currentIdx - 1) ?: currentKeyboardId

                ".next" -> presetKeyboardIds.getOrNull(currentIdx + 1) ?: currentKeyboardId

                ".last" -> lastKeyboardId

                ".last_lock" -> lastLockKeyboardId

                ".ascii" -> {
                    var ascii = activeKeyboard?.asciiKeyboard
                    if (ascii.isNullOrEmpty()) {
                        ascii = lastLockKeyboardId
                    }
                    if (presetKeyboardIds.contains(ascii)) ascii else currentKeyboardId
                }

                else -> {
                    id.ifEmpty {
                        if (activeKeyboard?.isLock == true) currentKeyboardId else lastLockKeyboardId
                    }
                }
            }
        var final = dot.ifEmpty { smartMatchKeyboard() }

        // 切换到横屏布局
        if (service.isLandscapeMode()) {
            val landscape =
                theme.presetKeyboards[final]?.landscapeKeyboard ?: ""
            if (landscape.isNotEmpty() && presetKeyboardIds.contains(landscape)) final = landscape
        }
        return final
    }

    fun switchKeyboard(to: String) {
        val target = evalKeyboard(to)
        ContextCompat.getMainExecutor(service).execute {
            if (cachedKeyboards.containsKey(target)) {
                if (target == currentKeyboardId) return@execute
            }
            detachCurrentView()
            attachKeyboard(target)
        }
        Timber.d("Switched to keyboard: $target")
    }

    fun refreshKeyboards(isAll: Boolean = false) {
        val id = currentKeyboardId.ifEmpty { return }
        detachCurrentView()
        if (isAll) {
            cachedKeyboards.clear()
        } else {
            cachedKeyboards.remove(id)
        }
        attachKeyboard(id)
    }

    /** Repaints the keyboard after a color-scheme switch; keys re-resolve their colors. */
    override fun refreshColors() {
        currentKeyboardView?.invalidateAllKeys()
    }

    override fun onStartInput(info: EditorInfo) {
        showIdlePunctuation()
        val targetKeyboard =
            when (info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) {
                EditorInfo.IME_FLAG_FORCE_ASCII -> ".ascii"

                else -> {
                    when (info.inputType and InputType.TYPE_MASK_CLASS) {
                        InputType.TYPE_CLASS_NUMBER,
                        InputType.TYPE_CLASS_PHONE,
                        InputType.TYPE_CLASS_DATETIME,
                        -> "number"

                        InputType.TYPE_CLASS_TEXT -> {
                            when (info.inputType and InputType.TYPE_MASK_VARIATION) {
                                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                                -> ".ascii"

                                else -> ""
                            }
                        }

                        else -> ""
                    }
                }
            }
        switchKeyboard(targetKeyboard)
        val isAsciiMode = rime.run { statusCached }.isAsciiMode
        if (targetKeyboard == ".ascii" || targetKeyboard == "number") {
            if (tempAsciiMode == null) {
                tempAsciiMode = isAsciiMode
            }
            if (!isAsciiMode) {
                service.postRimeJob { setRuntimeOption("ascii_mode", true) }
            }
        } else {
            tempAsciiMode?.let { saved ->
                if (isAsciiMode != saved) {
                    service.postRimeJob { setRuntimeOption("ascii_mode", saved) }
                }
                tempAsciiMode = null
            } ?: activeKeyboard?.let {
                if (theme.style.resetAsciiModeOnFocusChange) {
                    val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode
                    if (isAsciiMode != targetMode) {
                        service.postRimeJob { setRuntimeOption("ascii_mode", targetMode) }
                    }
                }
            }
        }
    }

    private fun dispatchCapsState(setShift: (Boolean, Boolean) -> Unit) {
        val status = rime.run { statusCached }
        // TODO: 启用自动首句大写后，点击方向键时，保持Shift锁定状态功能将无法生效
        if (theme.style.autoCaps == true && status.isAsciiMode && currentKeyboardView?.isCapsOn == false) {
            setShift(false, cursorCapsMode != 0)
        }
    }

    override fun onKeyAppearanceUpdate(composing: Boolean, menu: Boolean, paging: Boolean) {
        if (!rime.run { statusCached }.isAsciiMode) {
            activeKeyboard?.appearanceStateKeys?.forEach { key ->
                currentKeyboardView?.invalidateKeyByIndex(key.index)
            }
        }
    }

    override fun onSelectionUpdate(
        start: Int,
        end: Int,
    ) {
        dispatchCapsState { on, shifted ->
            activeKeyboard?.setShifted(on, shifted)?.let { if (it) currentKeyboardView?.invalidateAllKeys() }
        }
    }

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        switchKeyboard(".default")
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        val option = value.option
        when {
            option.startsWith("_keyboard_") -> {
                val target = option.removePrefix("_keyboard_")
                if (target.isNotEmpty()) {
                    switchKeyboard(target)
                }
            }

            option.startsWith("_key_") -> {
                val what = option.removePrefix("_key_")
                if (what.isNotEmpty() && value.value) {
                    commonKeyboardActionListener
                        .listener
                        .onAction(theme.resolveAction(what))
                }
            }

            option == "ascii_mode" && !value.value -> {
                // 回到中文时，释放 Shift 锁定
                activeKeyboard?.resetShift()
            }
        }
        currentKeyboardView?.invalidateAllKeys()
    }

    override fun onAttached() {
    }

    override fun onDetached() {
        currentKeyboardView?.onDetach()
    }
}
