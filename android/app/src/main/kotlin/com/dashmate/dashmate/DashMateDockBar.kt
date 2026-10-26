package com.dashmate.dashmate

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DashMateDockBar(
    context: Context,
    private val onPageSelected: (String) -> Unit,
    /** Closes DashMate and returns to the Android Auto screen (the red button at the end). */
    private val onExit: () -> Unit = {},
    /** ‹ Back while a website is open: previous page, or back to the deck. */
    private val onBack: () -> Unit = {},
) : FrameLayout(context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("dd_car_browser", Context.MODE_PRIVATE)
    private val density: Float = context.resources.displayMetrics.density

    // Day / night colours; set by applyTheme (called with DashMateTheme.isDark once built).
    private var activeItemBg = Color.parseColor("#2DD4BF")
    private var activeIconTint = Color.parseColor("#042F2E")
    private var dockBgColor = Color.parseColor("#181B21")
    private var dockStrokeColor = Color.parseColor("#2A2F37")
    private var inactiveItemBg = Color.parseColor("#20242B")
    private var inactiveIconTint = Color.parseColor("#F1F5F9")

    private val expandInterpolator = OvershootInterpolator(1.3f)
    private val collapseInterpolator = PathInterpolator(0.4f, 0.0f, 0.2f, 1.0f)

    private val itemViews: MutableMap<String, ImageView> = LinkedHashMap()

    var isExpanded: Boolean = true
        private set

    var currentPage: String = "home"
        private set

    private var downX = 0f
    private var downY = 0f
    private var initialMarginX = 0
    private var initialMarginY = 0
    private var isDragging = false
    private val dragThreshold = dp(10)

    private val launcherBtn: ImageView
    private val dockPill: LinearLayout
    private val appsIcon: ImageView
    private val exitBtn: ImageView
    private val backBtn: ImageView

    init {
        clipChildren = false
        clipToPadding = false

        launcherBtn = ImageView(context).apply {
            setImageResource(R.drawable.dd_ic_grid_circles)
            imageTintList = ColorStateList.valueOf(inactiveIconTint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dockBgColor)
                setStroke(dp(1), dockStrokeColor)
            }
            elevation = dp(4).toFloat()
            setOnClickListener { toggleExpand() }
        }

        dockPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(dockBgColor)
                setStroke(dp(1), dockStrokeColor)
            }
            elevation = dp(4).toFloat()
        }

        appsIcon = createIconButton("apps", R.drawable.dd_ic_grid_circles, "Apps Launcher") {
            toggleExpand()
        }
        dockPill.addView(appsIcon)

        val items = listOf(
            Triple("home", R.drawable.dd_ic_home, "Home"),
            Triple("lyrics", R.drawable.dd_ic_lyrics, "Lyrics"),
            Triple("prayer", R.drawable.dd_ic_prayer, "Prayer Times"),
            Triple("radio", R.drawable.dd_ic_radio, "Radio"),
            // No "custom" button: it looked like a second Home (owner, 25 Oct). The Custom page
            // (forecast / air / radar) opens from the weather card on Home instead.
            Triple("web", R.drawable.dd_ic_web, "Browser"),
            Triple("nav", R.drawable.dd_ic_nav, "Navigation (Waze Live Map)"),
        )
        // ‹ Back sits right after Home and only shows while a website is open (the browser has no bar of its own).
        backBtn = createIconButton("back", R.drawable.dd_ic_back, "Back") { onBack() }.apply { visibility = View.GONE }
        for ((id, drawableRes, desc) in items) {
            val btn = createIconButton(id, drawableRes, desc) { selectPage(id) }
            itemViews[id] = btn
            dockPill.addView(btn)
            if (id == "home") dockPill.addView(backBtn)
        }

        // Exit: not a page, so it is never highlighted; red so it is easy to find.
        exitBtn = createIconButton("exit", R.drawable.dd_ic_exit, "Exit DashMate") { onExit() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        }
        dockPill.addView(exitBtn)

        addView(dockPill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(
            launcherBtn,
            LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.TOP or Gravity.START },
        )

        applyTheme(DashMateTheme.isDark)
        setExpandedState(expanded = true, animate = false)
    }

    /** Recolours the dock for day (white) or night (dark). Colours only; nothing else changes. */
    fun applyTheme(dark: Boolean) {
        if (dark) {
            dockBgColor = Color.parseColor("#181B21")
            dockStrokeColor = Color.parseColor("#2A2F37")
            inactiveItemBg = Color.parseColor("#20242B")
            inactiveIconTint = Color.parseColor("#F1F5F9")
            activeItemBg = Color.parseColor("#2DD4BF")
            activeIconTint = Color.parseColor("#042F2E")
        } else {
            dockBgColor = Color.argb(245, 255, 255, 255) // #FFFFFF at ~96%
            dockStrokeColor = Color.parseColor("#E2E8F0")
            inactiveItemBg = Color.parseColor("#F1F5F9")
            inactiveIconTint = Color.parseColor("#0F172A")
            activeItemBg = Color.parseColor("#0F766E")
            activeIconTint = Color.WHITE
        }
        (dockPill.background as? GradientDrawable)?.apply {
            setColor(dockBgColor)
            setStroke(dp(1), dockStrokeColor)
        }
        (launcherBtn.background as? GradientDrawable)?.apply {
            setColor(dockBgColor)
            setStroke(dp(1), dockStrokeColor)
        }
        launcherBtn.imageTintList = ColorStateList.valueOf(inactiveIconTint)
        appsIcon.imageTintList = ColorStateList.valueOf(inactiveIconTint)
        backBtn.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(inactiveItemBg) }
        backBtn.imageTintList = ColorStateList.valueOf(inactiveIconTint)
        // Exit stays red so it is easy to find.
        exitBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (dark) "#F87171" else "#DC2626"))
        }
        exitBtn.imageTintList = ColorStateList.valueOf(if (dark) Color.parseColor("#1F0A0A") else Color.WHITE)
        updatePageHighlight(currentPage)
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private fun createIconButton(id: String, drawableRes: Int, desc: String, onClick: () -> Unit): ImageView {
        val size = dp(38)
        return ImageView(context).apply {
            tag = id
            setImageResource(drawableRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            contentDescription = desc
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            setOnClickListener { onClick() }
        }
    }

    fun selectPage(page: String) {
        if (currentPage != page || page == "custom") {
            currentPage = page
            updatePageHighlight(page)
            onPageSelected(page)
        }
    }

    /** Shows the ‹ Back button only while a website is open. */
    fun setBackVisible(visible: Boolean) { backBtn.visibility = if (visible) View.VISIBLE else View.GONE }

    fun updateCurrentPage(page: String) {
        if (currentPage != page) {
            currentPage = page
            updatePageHighlight(page)
        }
    }

    private fun updatePageHighlight(activePage: String) {
        for ((id, btn) in itemViews) {
            val isActive = id == activePage
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isActive) activeItemBg else inactiveItemBg)
            }
            btn.imageTintList = ColorStateList.valueOf(if (isActive) activeIconTint else inactiveIconTint)
        }
    }

    fun toggleExpand() {
        setExpandedState(!isExpanded, animate = true)
    }

    private fun setExpandedState(expanded: Boolean, animate: Boolean) {
        isExpanded = expanded
        if (!animate) {
            if (expanded) {
                dockPill.visibility = View.VISIBLE
                dockPill.alpha = 1f
                dockPill.scaleX = 1f
                dockPill.scaleY = 1f
                launcherBtn.visibility = View.GONE
            } else {
                dockPill.visibility = View.GONE
                launcherBtn.visibility = View.VISIBLE
                launcherBtn.alpha = 1f
            }
            return
        }

        if (expanded) {
            dockPill.visibility = View.VISIBLE
            dockPill.alpha = 0f
            dockPill.scaleX = 0.7f
            dockPill.scaleY = 0.7f
            dockPill.pivotX = 0f
            dockPill.pivotY = dockPill.height / 2.0f
            launcherBtn.animate()
                .alpha(0f)
                .setDuration(120L)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        launcherBtn.visibility = View.GONE
                    }
                })
                .start()
            dockPill.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setInterpolator(expandInterpolator)
                .setDuration(220L)
                .setListener(null)
                .start()
        } else {
            launcherBtn.visibility = View.VISIBLE
            launcherBtn.alpha = 0f
            launcherBtn.animate()
                .alpha(1f)
                .setDuration(150L)
                .setListener(null)
                .start()
            dockPill.animate()
                .alpha(0f)
                .scaleX(0.7f)
                .scaleY(0.7f)
                .setInterpolator(collapseInterpolator)
                .setDuration(150L)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        dockPill.visibility = View.GONE
                    }
                })
                .start()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                initialMarginX = (layoutParams as? LayoutParams)?.leftMargin ?: 0
                initialMarginY = (layoutParams as? LayoutParams)?.topMargin ?: 0
                isDragging = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (abs(dx) > dragThreshold || abs(dy) > dragThreshold) {
                    isDragging = true
                    return true
                }
                return false
            }
            else -> return false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val parentView = parent as? ViewGroup ?: return super.onTouchEvent(event)
        val lp = layoutParams as? LayoutParams ?: return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val maxMarginX = max(0, parentView.width - width)
                    val maxMarginY = max(0, parentView.height - height)
                    lp.leftMargin = min(maxMarginX, max(0, (initialMarginX + dx).toInt()))
                    lp.topMargin = min(maxMarginY, max(0, (initialMarginY + dy).toInt()))
                    layoutParams = lp
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    savePosition(lp.leftMargin, lp.topMargin)
                    isDragging = false
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun savePosition(x: Int, y: Int) {
        prefs.edit().putInt("dock_x", x).putInt("dock_y", y).apply()
    }

    fun restorePosition(parentWidth: Int, parentHeight: Int) {
        val defaultX = dp(16)
        val defaultY = dp(12)
        val savedX = prefs.getInt("dock_x", defaultX)
        val savedY = prefs.getInt("dock_y", defaultY)
        val maxMarginX = max(0, parentWidth - dp(240))
        val maxMarginY = max(0, parentHeight - dp(56))
        val clampedX = min(maxMarginX, max(0, savedX))
        val clampedY = min(maxMarginY, max(0, savedY))
        (layoutParams as? LayoutParams)?.let {
            it.leftMargin = clampedX
            it.topMargin = clampedY
            layoutParams = it
        }
    }
}
