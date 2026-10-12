package com.vigilix.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vigilix.app.databinding.ActivitySubscriptionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla de suscripción Vigilix Pro.
 * Muestra: estado actual, funciones gratis vs Pro, botones de compra mensual/anual,
 * restaurar compras y gestionar en Play Store.
 */
class SubscriptionActivity : AppCompatActivity() {

    private lateinit var b: ActivitySubscriptionBinding
    private val entitlementManager by lazy { EntitlementManager.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupToolbar()
        setupClickListeners()
        observeEntitlement()
        loadFeatures()
    }

    private fun setupToolbar() {
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        b.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupClickListeners() {
        b.btnSubMonthly.setOnClickListener { launchPurchase(EntitlementManager.PRO_MONTHLY) }
        b.btnSubYearly.setOnClickListener { launchPurchase(EntitlementManager.PRO_YEARLY) }
        b.btnSubRestore.setOnClickListener { restorePurchases() }
        b.btnSubManage.setOnClickListener { openPlayStoreSubscription() }
    }

    private fun observeEntitlement() {
        entitlementManager.isProLiveData.observe(this) { isPro ->
            updateUIForEntitlement(isPro)
        }
        entitlementManager.billingReady.observe(this) { ready ->
            b.btnSubMonthly.isEnabled = ready
            b.btnSubYearly.isEnabled = ready
            b.btnSubRestore.isEnabled = ready
            if (!ready) {
                showError(getString(R.string.sub_error_load))
            } else {
                hideError()
            }
        }
    }

    private fun updateUIForEntitlement(isPro: Boolean) {
        if (isPro) {
            b.tvSubStatus.text = getString(R.string.sub_current_plan_pro, getString(R.string.sub_monthly))
            b.tvSubTrial.visibility = View.GONE
            b.btnSubMonthly.visibility = View.GONE
            b.btnSubYearly.visibility = View.GONE
            b.btnSubRestore.visibility = View.GONE
            b.btnSubManage.isVisible = true
            b.btnSubManage.text = getString(R.string.sub_manage)
            showSuccess(getString(R.string.sub_already_pro))
        } else {
            b.tvSubStatus.text = getString(R.string.sub_current_plan_free)
            b.tvSubTrial.text = getString(R.string.sub_trial_available)
            b.tvSubTrial.isVisible = true
            b.btnSubMonthly.isVisible = true
            b.btnSubYearly.isVisible = true
            b.btnSubRestore.isVisible = true
            b.btnSubManage.isVisible = false
            hideError()
        }
    }

    private fun loadFeatures() {
        // Funciones gratis
        val freeFeatures = listOf(
            Pair(R.string.sub_free_feature_scan, R.drawable.ic_shield),
            Pair(R.string.sub_free_feature_vault, R.drawable.ic_lock),
            Pair(R.string.sub_free_feature_perms, R.drawable.ic_apps),
            Pair(R.string.sub_free_feature_network, R.drawable.ic_open),
            Pair(R.string.sub_free_feature_apk, R.drawable.ic_file),
            Pair(R.string.sub_free_feature_scam, R.drawable.ic_status_warn),
            Pair(R.string.sub_free_feature_duress, R.drawable.ic_chevron),
            Pair(R.string.sub_free_feature_dns, R.drawable.ic_status_info),
            Pair(R.string.sub_free_feature_autofill, R.drawable.ic_copy),
            Pair(R.string.sub_free_feature_vt, R.drawable.ic_status_ok),
            Pair(R.string.sub_free_feature_backup, R.drawable.ic_status_bad),
        )
        freeFeatures.forEach { (titleRes, iconRes) ->
            b.containerFreeFeatures.addView(createFeatureRow(titleRes, iconRes, isPro = false))
        }

        // Funciones Pro
        val proFeatures = listOf(
            Pair(R.string.sub_feature_cloud_sync, R.drawable.ic_shield),
            Pair(R.string.sub_feature_extra_themes, R.drawable.ic_lock),
            Pair(R.string.sub_feature_export, R.drawable.ic_file),
            Pair(R.string.sub_feature_unlimited_history, R.drawable.ic_open),
            Pair(R.string.sub_feature_priority_support, R.drawable.ic_status_ok),
            Pair(R.string.sub_feature_behavior_analysis, R.drawable.ic_status_warn),
        )
        proFeatures.forEach { (titleRes, iconRes) ->
            b.containerProFeatures.addView(createFeatureRow(titleRes, iconRes, isPro = true))
        }
    }

    private fun createFeatureRow(titleRes: Int, iconRes: Int, isPro: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val icon = android.widget.ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(24, 24)
            setColorFilter(androidx.core.content.ContextCompat.getColor(this@SubscriptionActivity, if (isPro) R.color.vx_accent else R.color.vx_text_muted))
        }

        val title = android.widget.TextView(this).apply {
            text = getString(titleRes)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 0, 0)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@SubscriptionActivity, if (isPro) R.color.vx_accent_text else R.color.vx_text))
        }

        val check = android.widget.ImageView(this).apply {
            setImageResource(if (isPro) R.drawable.ic_status_warn else R.drawable.ic_status_ok)
            layoutParams = LinearLayout.LayoutParams(24, 24)
            setColorFilter(androidx.core.content.ContextCompat.getColor(this@SubscriptionActivity, if (isPro) R.color.vx_orange else R.color.vx_green))
        }

        row.addView(icon)
        row.addView(title)
        row.addView(check)
        return row
    }

    private fun launchPurchase(productId: String) {
        setLoading(true)
        hideError()

        val isMonthly = productId == EntitlementManager.PRO_MONTHLY
        if (isMonthly) {
            entitlementManager.launchPurchase(this) { success ->
                onPurchaseResult(success, isMonthly)
            }
        } else {
            entitlementManager.launchPurchaseYearly(this) { success ->
                onPurchaseResult(success, isMonthly)
            }
        }
    }

    private fun onPurchaseResult(success: Boolean, isMonthly: Boolean) {
        setLoading(false)
        if (success) {
            // El listener de purchasesUpdatedListener actualizará isProLiveData
            showSuccess(getString(R.string.sub_purchase_success))
        } else {
            showError(getString(R.string.sub_error_purchase, "Compra cancelada o fallida"))
        }
    }

    private fun restorePurchases() {
        setLoading(true)
        hideError()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    entitlementManager.refreshPurchases()
                    true
                } catch (e: Exception) {
                    false
                }
            }
            setLoading(false)
            if (!result) {
                showError(getString(R.string.sub_error_purchase, "Error al restaurar"))
            } else {
                toast(R.string.sub_purchase_pending)
            }
        }
    }

    private fun openPlayStoreSubscription() {
        val url = "https://play.google.com/store/account/subscriptions?sku=vigilix_pro_monthly&package=com.vigilix.app"
        openUrl(url)
    }

    private fun setLoading(loading: Boolean) {
        b.progressSub.isVisible = loading
        b.btnSubMonthly.isEnabled = !loading && !entitlementManager.isPro
        b.btnSubYearly.isEnabled = !loading && !entitlementManager.isPro
        b.btnSubRestore.isEnabled = !loading
    }

    private fun showError(message: String) {
        b.calloutSubError.show(com.vigilix.app.Status.BAD, getString(R.string.error_engine_title), message)
    }

    private fun showSuccess(message: String) {
        b.calloutSubError.show(com.vigilix.app.Status.OK, getString(R.string.dns_done_title), message)
    }

    private fun hideError() {
        b.calloutSubError.hide()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}