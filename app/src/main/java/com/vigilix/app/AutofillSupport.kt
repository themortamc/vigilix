package com.vigilix.app

import android.app.assist.AssistStructure
import android.content.Context
import android.content.IntentSender
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import java.util.Locale

/** Lo que se encontró en la pantalla donde la persona quiere iniciar sesión. */
class LoginFields(
    val usernameIds: List<AutofillId>,
    val passwordIds: List<AutofillId>,
    /** Dominio verificado por el sistema (navegadores compatibles). Null en apps comunes. */
    val webDomain: String?,
    val packageName: String?,
) {
    val isLogin: Boolean get() = passwordIds.isNotEmpty() || usernameIds.isNotEmpty()
    val allIds: List<AutofillId> get() = usernameIds + passwordIds
}

/** Lee la estructura de la pantalla y localiza los campos de usuario y contraseña. */
object AutofillParser {

    private class Field(val id: AutofillId, val isPassword: Boolean, val isUsername: Boolean, val isText: Boolean)

    fun parse(structure: AssistStructure): LoginFields {
        val fields = ArrayList<Field>()
        var webDomain: String? = null

        fun visit(node: AssistStructure.ViewNode) {
            if (webDomain == null && !node.webDomain.isNullOrBlank()) webDomain = node.webDomain
            val id = node.autofillId
            if (id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT && node.visibility == View.VISIBLE) {
                fields.add(
                    Field(id, isPasswordField(node), isUsernameField(node), isTextField(node)),
                )
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) {
            visit(structure.getWindowNodeAt(i).rootViewNode)
        }

        val passwords = fields.filter { it.isPassword }
        val usernames = fields.filter { it.isUsername && !it.isPassword }.toMutableList()
        if (usernames.isEmpty() && passwords.isNotEmpty()) {
            // Sin pistas: el campo de texto inmediatamente anterior a la contraseña suele ser el usuario.
            val firstPassword = fields.indexOf(passwords.first())
            fields.subList(0, firstPassword).lastOrNull { it.isText && !it.isPassword }?.let { usernames.add(it) }
        }
        return LoginFields(
            usernameIds = usernames.map { it.id },
            passwordIds = passwords.map { it.id },
            webDomain = webDomain,
            packageName = structure.activityComponent?.packageName,
        )
    }

    private fun isTextField(node: AssistStructure.ViewNode): Boolean =
        (node.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT || node.htmlInfo != null

    private fun isPasswordField(node: AssistStructure.ViewNode): Boolean {
        val hints = node.autofillHints
        if (hints != null && hints.any { it == View.AUTOFILL_HINT_PASSWORD }) return true
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val isTextClass = (node.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
        if (isTextClass && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        ) {
            return true
        }
        return node.htmlInfo?.attributes?.any {
            it.first.equals("type", ignoreCase = true) && it.second.equals("password", ignoreCase = true)
        } == true
    }

    private fun isUsernameField(node: AssistStructure.ViewNode): Boolean {
        val hints = node.autofillHints
        if (hints != null && hints.any {
                it == View.AUTOFILL_HINT_USERNAME || it == View.AUTOFILL_HINT_EMAIL_ADDRESS
            }
        ) {
            return true
        }
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        ) {
            return true
        }
        val html = node.htmlInfo?.attributes
        if (html != null) {
            val type = html.firstOrNull { it.first.equals("type", true) }?.second?.lowercase(Locale.ROOT)
            val autocomplete = html.firstOrNull { it.first.equals("autocomplete", true) }?.second?.lowercase(Locale.ROOT)
            if (type == "email" || autocomplete == "username" || autocomplete == "email") return true
        }
        val words = listOf(node.idEntry, node.hint).filterNotNull().joinToString(" ").lowercase(Locale.ROOT)
        return listOf("user", "usuario", "email", "correo", "login", "mail").any { words.contains(it) }
    }
}

/** Arma las respuestas de autocompletado. */
object AutofillResponses {
    private const val MAX_DATASETS = 5

    @Suppress("DEPRECATION")
    fun fillResponse(context: Context, fields: LoginFields): FillResponse? {
        val matches = VaultStore.matchesFor(fields.webDomain, fields.packageName).take(MAX_DATASETS)
        if (matches.isEmpty()) return null

        val builder = FillResponse.Builder()
        var added = 0
        for (credential in matches) {
            val subtitle = credential.username.ifEmpty { credential.host ?: "" }
            val dataset = Dataset.Builder(presentation(context, credential.title.ifEmpty { credential.host ?: "?" }, subtitle))
            var values = 0
            if (credential.username.isNotEmpty()) {
                for (id in fields.usernameIds) {
                    dataset.setValue(id, AutofillValue.forText(credential.username))
                    values++
                }
            }
            for (id in fields.passwordIds) {
                dataset.setValue(id, AutofillValue.forText(credential.password))
                values++
            }
            // Un dataset sin valores es inválido: se omite.
            if (values > 0) {
                builder.addDataset(dataset.build())
                added++
            }
        }
        return if (added > 0) builder.build() else null
    }

    /** Respuesta que pide desbloquear la bóveda antes de mostrar nada. */
    fun lockedResponse(context: Context, fields: LoginFields): FillResponse {
        val intent = Intent(context, AutofillAuthActivity::class.java)
            .putParcelableArrayListExtra(AutofillAuthActivity.EXTRA_USERNAME_IDS, ArrayList(fields.usernameIds))
            .putParcelableArrayListExtra(AutofillAuthActivity.EXTRA_PASSWORD_IDS, ArrayList(fields.passwordIds))
            .putExtra(AutofillAuthActivity.EXTRA_DOMAIN, fields.webDomain)
            .putExtra(AutofillAuthActivity.EXTRA_PACKAGE, fields.packageName)
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val sender: IntentSender = PendingIntent.getActivity(context, 0, intent, flags).intentSender
        return FillResponse.Builder()
            .setAuthentication(
                fields.allIds.toTypedArray(),
                sender,
                presentation(context, context.getString(R.string.af_unlock_title), context.getString(R.string.af_unlock_subtitle)),
            )
            .build()
    }

    private fun presentation(context: Context, title: String, subtitle: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.autofill_item)
        views.setTextViewText(R.id.afTitle, title)
        views.setTextViewText(R.id.afSubtitle, subtitle)
        return views
    }
}
