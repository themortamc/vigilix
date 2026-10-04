package com.vigilix.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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

// ---------------------------------------------------------------------------------------------
// Utilidades de pantalla compartidas
// ---------------------------------------------------------------------------------------------

fun Context.toast(@StringRes id: Int, vararg args: Any) {
    Toast.makeText(this, getString(id, *args), Toast.LENGTH_SHORT).show()
}

/** Copia al portapapeles. Lo sensible se marca como tal y se borra solo a los 45 segundos. */
fun Context.copyToClipboard(text: String, sensitive: Boolean) {
    ClipboardHelper.copy(this, text, sensitive)
    if (sensitive) toast(R.string.copied_sensitive, ClipboardHelper.CLEAR_AFTER_SECONDS) else toast(R.string.copied)
}

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        toast(R.string.error_no_browser)
    }
}

fun Context.openAppSettings(pkg: String) {
    try {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        toast(R.string.error_open_settings)
    }
}

/** Diálogo con un campo de texto. `secret` oculta lo escrito y evita sugerencias del teclado. */
fun AppCompatActivity.askText(
    @StringRes title: Int,
    @StringRes hint: Int,
    secret: Boolean,
    @StringRes message: Int? = null,
    onOk: (String) -> Unit,
) {
    val density = resources.displayMetrics.density
    val layout = TextInputLayout(this)
    layout.hint = getString(hint)
    val input = TextInputEditText(layout.context)
    input.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
        if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
    layout.addView(input)
    val holder = FrameLayout(this)
    val pad = (24 * density).toInt()
    holder.setPadding(pad, (8 * density).toInt(), pad, 0)
    holder.addView(layout)

    val builder = MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(holder)
        .setPositiveButton(R.string.save) { _, _ -> onOk(input.text?.toString().orEmpty().trim()) }
        .setNegativeButton(R.string.cancel, null)
    if (message != null) builder.setMessage(message)
    builder.show()
}
