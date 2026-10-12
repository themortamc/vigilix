package com.vigilix.app.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.android.material.chip.Chip
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.vigilix.app.R
import com.vigilix.app.Status

/**
 * VxComponents - Vigilix Design System Component Library
 *
 * Reusable UI components built on design_tokens.xml
 * All components respect: light/dark theme, accessibility, motion preferences
 */

// ========================================================================
// VxCard - Elevated container with consistent styling
// ========================================================================

class VxCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        setupCard()
    }

    private fun setupCard() {
        val radius = resources.getDimensionPixelSize(R.dimen.vx_radius_card)
        val elevation = resources.getDimensionPixelSize(R.dimen.vx_elevation_level_1)
        val bgColor = ContextCompat.getColor(context, R.color.vx_color_surface)

        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(bgColor)
        }

        ViewCompat.setBackground(this, shape)
        ViewCompat.setElevation(this, elevation.toFloat())
        setClipToOutline(true)

        // Default padding
        val inset = resources.getDimensionPixelSize(R.dimen.vx_inset_medium)
        setPadding(inset, inset, inset, inset)
    }

    fun setElevationLevel(level: Int) {
        val elevation = when (level) {
            0 -> resources.getDimensionPixelSize(R.dimen.vx_elevation_level_0)
            1 -> resources.getDimensionPixelSize(R.dimen.vx_elevation_level_1)
            2 -> resources.getDimensionPixelSize(R.dimen.vx_elevation_level_2)
            3 -> resources.getDimensionPixelSize(R.dimen.vx_elevation_level_3)
            4 -> resources.getDimensionPixelSize(R.dimen.vx_elevation_level_4)
            5 -> resources.getDimensionPixelSize(R.dimen.vx_elevation_level_5)
            else -> resources.getDimensionPixelSize(R.dimen.vx_elevation_level_1)
        }
        ViewCompat.setElevation(this, elevation.toFloat())
    }
}

// ========================================================================
// VxButton - Consistent button styling with variants
// ========================================================================

class VxButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : androidx.appcompat.widget.AppCompatButton(context, attrs, defStyleAttr) {

    enum class Variant {
        PRIMARY, SECONDARY, OUTLINED, TEXT, TONAL
    }

    private var variant: Variant = Variant.PRIMARY
    private var isLoading = false
    private var originalText: CharSequence? = null

    init {
        setupButton()
    }

    private fun setupButton() {
        val radius = resources.getDimensionPixelSize(R.dimen.vx_radius_button)
        val minHeight = resources.getDimensionPixelSize(R.dimen.vx_touch_target_min)
        val fontWeight = resources.getInteger(R.integer.vx_font_weight_medium)

        setMinHeight(minHeight)
        setAllCaps(false)
        setLetterSpacing(resources.getInteger(R.integer.vx_letter_spacing_normal) / 1000f)
        setTypeface(typeface, android.graphics.Typeface.NORMAL)

        // Apply default variant
        applyVariant(variant)

        // Set up ripple
        val rippleColor = ContextCompat.getColorStateList(context, R.color.vx_accent_soft) ?: ColorStateList.valueOf(ContextCompat.getColor(context, R.color.vx_accent_soft))
        background = RippleDrawable(rippleColor, background, null).apply {
            setRadius(radius)
        }
    }

    fun setVariant(variant: Variant) {
        this.variant = variant
        applyVariant(variant)
    }

    private fun applyVariant(variant: Variant) {
        when (variant) {
            Variant.PRIMARY -> {
                setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.vx_color_accent_primary)))
                setTextColor(ContextCompat.getColor(context, R.color.vx_color_text_on_accent))
            }
            Variant.SECONDARY -> {
                setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.vx_color_surface_variant)))
                setTextColor(ContextCompat.getColor(context, R.color.vx_color_text_primary))
            }
            Variant.OUTLINED -> {
                setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.vx_color_surface)))
                setTextColor(ContextCompat.getColor(context, R.color.vx_color_accent_primary))
                // Stroke handled by background drawable
            }
            Variant.TEXT -> {
                setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT))
                setTextColor(ContextCompat.getColor(context, R.color.vx_color_accent_primary))
            }
            Variant.TONAL -> {
                setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.vx_color_accent_container)))
                setTextColor(ContextCompat.getColor(context, R.color.vx_color_accent_on_container))
            }
        }
    }

    fun showLoading(loading: Boolean) {
        isLoading = loading
        if (loading) {
            originalText = text
            text = context.getString(R.string.vx_loading)
            isEnabled = false
        } else {
            text = originalText ?: ""
            isEnabled = true
        }
    }
}

// ========================================================================
// VxChip - Selectable chip with consistent styling
// ========================================================================

class VxChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : Chip(context, attrs, defStyleAttr) {

    init {
        setupChip()
    }

    private fun setupChip() {
        val radius = context.resources.getDimensionPixelSize(R.dimen.vx_radius_chip)
        val minHeight = context.resources.getDimensionPixelSize(R.dimen.vx_touch_target_min)

        setMinHeight(minHeight)
        setChipCornerRadius(radius.toFloat())
        setCheckable(true)
        setClickable(true)
        setFocusable(true)

        // Default colors
        setChipBackgroundColor(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.vx_color_surface_variant)))
        setTextColor(ContextCompat.getColor(context, R.color.vx_color_text_primary))
        // Note: checkedIconTintList may not be available in this version
        // setCheckedIconTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.vx_color_accent_primary)))
    }

    override fun setSelected(selected: Boolean) {
        isChecked = selected
    }
}

// ========================================================================
// VxCallout - Status callout (info/warning/error/success)
// ========================================================================

class VxCallout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconView: ImageView
    private val titleView: TextView
    private val bodyView: TextView
    private val container: LinearLayout

    init {
        orientation = VERTICAL
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.vx_callout, this, true)

        iconView = findViewById(R.id.vx_callout_icon)
        titleView = findViewById(R.id.vx_callout_title)
        bodyView = findViewById(R.id.vx_callout_body)
        container = findViewById(R.id.vx_callout_container)

        val radius = resources.getDimensionPixelSize(R.dimen.vx_radius_medium)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
        }
        ViewCompat.setBackground(container, bg)
    }

    fun show(status: Status, @StringRes titleRes: Int, @StringRes bodyRes: Int? = null) {
        show(status, context.getString(titleRes), bodyRes?.let { context.getString(it) })
    }

    fun show(status: Status, title: CharSequence, body: CharSequence? = null) {
        val colorRes = when (status) {
            Status.OK -> R.color.vx_color_success
            Status.WARN -> R.color.vx_color_warning
            Status.BAD -> R.color.vx_color_error
            Status.INFO -> R.color.vx_color_info
        }

        val bgRes = when (status) {
            Status.OK -> R.color.vx_color_success_container
            Status.WARN -> R.color.vx_color_warning_container
            Status.BAD -> R.color.vx_color_error_container
            Status.INFO -> R.color.vx_color_info_container
        }

        val iconRes = when (status) {
            Status.OK -> R.drawable.ic_status_ok
            Status.WARN -> R.drawable.ic_status_warn
            Status.BAD -> R.drawable.ic_status_bad
            Status.INFO -> R.drawable.ic_status_info
        }

        val color = ContextCompat.getColor(context, colorRes)
        val bgColor = ContextCompat.getColor(context, bgRes)

        container.setBackgroundColor(bgColor)
        iconView.setImageResource(iconRes)
        iconView.setColorFilter(color)
        titleView.text = title
        titleView.setTextColor(color)

        body?.let {
            bodyView.text = it
            bodyView.visibility = View.VISIBLE
        } ?: run {
            bodyView.visibility = View.GONE
        }

        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }
}

// ========================================================================
// VxProgress - Consistent progress indicators
// ========================================================================

class VxProgress @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val progressBar: ProgressBar
    private val labelView: TextView?

    init {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.vx_progress, this, true)

        progressBar = findViewById(R.id.vx_progress_bar)
        labelView = findViewById(R.id.vx_progress_label)

        val color = ContextCompat.getColor(context, R.color.vx_color_accent_primary)
        progressBar.indeterminateTintList = ColorStateList.valueOf(color)
    }

    fun setProgress(progress: Int, max: Int = 100) {
        progressBar.max = max
        progressBar.progress = progress
        progressBar.isIndeterminate = false
    }

    fun setIndeterminate(indeterminate: Boolean) {
        progressBar.isIndeterminate = indeterminate
    }

    fun setLabel(@StringRes labelRes: Int?) {
        labelView?.let { tv ->
            labelRes?.let {
                tv.text = context.getString(labelRes)
                tv.visibility = View.VISIBLE
            } ?: run {
                tv.visibility = View.GONE
            }
        }
    }

    fun setLabel(label: CharSequence?) {
        labelView?.let { tv ->
            label?.let {
                tv.text = label
                tv.visibility = View.VISIBLE
            } ?: run {
                tv.visibility = View.GONE
            }
        }
    }
}

// ========================================================================
// VxEmptyState - Illustrated empty state with action
// ========================================================================

class VxEmptyState @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val illustrationView: ImageView
    private val titleView: TextView
    private val bodyView: TextView
    private val actionButton: VxButton?

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(
            resources.getDimensionPixelSize(R.dimen.vx_inset_large),
            resources.getDimensionPixelSize(R.dimen.vx_inset_large),
            resources.getDimensionPixelSize(R.dimen.vx_inset_large),
            resources.getDimensionPixelSize(R.dimen.vx_inset_large)
        )

        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.vx_empty_state, this, true)

        illustrationView = findViewById(R.id.vx_empty_illustration)
        titleView = findViewById(R.id.vx_empty_title)
        bodyView = findViewById(R.id.vx_empty_body)
        actionButton = findViewById(R.id.vx_empty_action)
    }

    fun setIllustration(@DrawableRes resId: Int) {
        illustrationView.setImageResource(resId)
        illustrationView.setColorFilter(ContextCompat.getColor(context, R.color.vx_color_text_tertiary))
    }

    fun setTitle(@StringRes titleRes: Int) {
        titleView.text = context.getString(titleRes)
    }

    fun setTitle(title: CharSequence) {
        titleView.text = title
    }

    fun setBody(@StringRes bodyRes: Int) {
        bodyView.text = context.getString(bodyRes)
    }

    fun setBody(body: CharSequence) {
        bodyView.text = body
    }

    fun setAction(@StringRes labelRes: Int, onClick: View.OnClickListener?) {
        actionButton?.let {
            it.text = context.getString(labelRes)
            it.setOnClickListener(onClick)
            it.visibility = View.VISIBLE
        }
    }

    fun hideAction() {
        actionButton?.visibility = View.GONE
    }
}

// ========================================================================
// VxDivider - Consistent divider
// ========================================================================

class VxDivider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        val thickness = resources.getDimensionPixelSize(R.dimen.vx_space_1)
        val color = ContextCompat.getColor(context, R.color.vx_color_border_light)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thickness)
        setBackgroundColor(color)
    }
}

// ========================================================================
// VxTooltip - Simple tooltip
// ========================================================================

class VxTooltip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val textView: TextView

    init {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.vx_tooltip, this, true)

        textView = findViewById(R.id.vx_tooltip_text)

        val radius = resources.getDimensionPixelSize(R.dimen.vx_radius_small)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(ContextCompat.getColor(context, R.color.vx_color_text_primary))
        }
        ViewCompat.setBackground(this, bg)
        ViewCompat.setElevation(this, resources.getDimensionPixelSize(R.dimen.vx_elevation_level_4).toFloat())
    }

    fun setText(@StringRes resId: Int) {
        textView.text = context.getString(resId)
    }

    fun setText(text: CharSequence) {
        textView.text = text
    }
}

// ========================================================================
// Extension functions for easy usage
// ========================================================================

fun Context.vxCard(): VxCard = VxCard(this)
fun Context.vxButton(variant: VxButton.Variant = VxButton.Variant.PRIMARY): VxButton = VxButton(this).apply { setVariant(variant) }
fun Context.vxChip(): VxChip = VxChip(this)
fun Context.vxCallout(): VxCallout = VxCallout(this)
fun Context.vxProgress(): VxProgress = VxProgress(this)
fun Context.vxEmptyState(): VxEmptyState = VxEmptyState(this)
fun Context.vxDivider(): VxDivider = VxDivider(this)
fun Context.vxTooltip(): VxTooltip = VxTooltip(this)