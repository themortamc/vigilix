package com.vigilix.app

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.vigilix.app.databinding.ViewCalloutBinding

/** Estado visual de un control o aviso (colores e íconos definidos en res/). */
enum class Status(
    @DrawableRes val background: Int,
    @ColorRes val color: Int,
    @DrawableRes val icon: Int,
) {
    OK(R.drawable.bg_callout_ok, R.color.vx_green, R.drawable.ic_status_ok),
    WARN(R.drawable.bg_callout_warn, R.color.vx_orange, R.drawable.ic_status_warn),
    BAD(R.drawable.bg_callout_bad, R.color.vx_red, R.drawable.ic_status_bad),
    INFO(R.drawable.bg_callout_info, R.color.vx_cyan, R.drawable.ic_status_info),
}

fun ImageView.setStatus(status: Status) {
    setImageResource(status.icon)
    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, status.color))
}

/** Muestra un "callout" (aviso con ícono) con el estado, título y detalle indicados. */
fun ViewCalloutBinding.show(status: Status, title: CharSequence, body: CharSequence? = null) {
    val color = ContextCompat.getColor(root.context, status.color)
    root.setBackgroundResource(status.background)
    ivCalloutIcon.setImageResource(status.icon)
    ivCalloutIcon.imageTintList = ColorStateList.valueOf(color)
    tvCalloutTitle.text = title
    tvCalloutTitle.setTextColor(color)
    tvCalloutBody.text = body
    tvCalloutBody.visibility = if (body.isNullOrEmpty()) View.GONE else View.VISIBLE
    root.visibility = View.VISIBLE
}

fun ViewCalloutBinding.hide() {
    root.visibility = View.GONE
}
