// SPDX-FileCopyrightText: 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.osfans.trime.data.theme.ThemeScope
import timber.log.Timber

/**
 * 九键左侧拼音滚动栏（Gboard 式独立侧栏）：候选注释（spelling_hints 输出的
 * 字母拼音）去重后纵向排列，内容超出栏高时可上下滚动；
 * 点按即"清组段 + 改输该全拼"，引擎重出该读音的候选。
 * 仅当候选携带 ≥2 个不同拼音注释时显示（26 键方案注释为空，自动隐藏）。
 */
class PinyinBarUi(
    context: Context,
    private val scope: ThemeScope,
) : ScrollView(context) {
    private val theme get() = scope.theme
    private val density = resources.displayMetrics.density

    // Read at use time so a scheme switch re-binds chips with the new colors.
    private val textColor: Int get() = scope.colors.keyTextColor
    private val textFont get() = theme.fonts.comment

    var onChipSelected: ((String) -> Unit)? = null

    private var current: List<Pair<String, String>> = emptyList()

    private val column =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

    init {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        // 圆角列底：叠在键盘留白列上，和键帽同款圆角（Gboard 左侧条同款层次）
        background =
            GradientDrawable().apply {
                cornerRadius = 6 * density
                // 与键帽同色：深色主题下是深灰列，浅色主题下是浅灰列
                setColor(runCatching { scope.colors.keyBackColor }.getOrDefault(SIDEBAR_SCRIM))
            }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = View.GONE
    }

    /** entries: 首音节 -> 重建后的完整音节序列（用 ' 分词） */
    fun update(pinyins: List<Pair<String, String>>) {
        val capped = pinyins.take(MAX_SHOWN)
        Timber.i("PinyinBar: update in=%s current=%s", pinyins, current)
        if (capped == current) return
        current = capped
        column.removeAllViews()
        if (capped.isEmpty()) {
            visibility = View.GONE
            Timber.i("PinyinBar: hidden (empty)")
            return
        }
        val padV = (3 * density).toInt()
        val padH = (4 * density).toInt()
        for ((chip, sequence) in capped) {
            val tv = TextView(context).apply {
                text = chip
                setTextSize(TypedValue.COMPLEX_UNIT_SP, CHIP_TEXT_SIZE_SP)
                includeFontPadding = false
                setTextColor(textColor)
                typeface = textFont
                gravity = Gravity.CENTER
                setPadding(padH, padV, padH, padV)
                isSingleLine = true
                isClickable = true
                isFocusable = false
                setOnClickListener {
                    Timber.i("PinyinBar: chip clicked %s -> %s", chip, sequence)
                    onChipSelected?.invoke(sequence)
                }
            }
            column.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        scrollTo(0, 0)
        visibility = View.VISIBLE
        post { Timber.i("PinyinBar: laid out %dx%d vis=%d chips=%d", width, height, visibility, column.childCount) }
    }

    /** 侧栏相对坐标命中测试（y 已包含滚动偏移），返回被点中的拼音。 */
    fun hitTest(x: Float, y: Float): String? {
        if (x < 0 || x > width || y < 0 || y > height) return null
        val yy = y + scrollY
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i)
            if (yy >= child.top && yy <= child.bottom) {
                val chip = (child as TextView).text.toString()
                return current.firstOrNull { it.first == chip }?.second
            }
        }
        return null
    }

    private companion object {
        const val MAX_SHOWN = 40
        const val CHIP_TEXT_SIZE_SP = 12f
        const val SIDEBAR_SCRIM = 0x22000000
    }
}
