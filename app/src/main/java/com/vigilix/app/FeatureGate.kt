package com.vigilix.app

import android.content.Context
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.andAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enum de features que pueden ser gateadas por modo/entitlement/remote config.
 */
enum class Feature {
    // Core (siempre disponibles)
    SCAN,
    VAULT,
    PRIVACY,

    // Condicionales por modo
    NETWORK_MONITOR,
    APK_ANALYZER,
    SCAM_DETECTION,
    RESTRICT_BACKGROUND,
    DNS_WITHOUT_ADB,
    SYSTEM_APP_SCAN,
    BEHAVIOR_ANALYSIS,

    // Pro only
    CLOUD_SYNC,
    EXTRA_THEMES,
    EXPORT_CSV,
    UNLIMITED_HISTORY,
    PRIORITY_SUPPORT,
    BEHAVIOR_ANALYSIS_SANDBOX
}

/**
 * Feature Gate centralizado. Decide qué features están disponibles según:
 * - Modo actual (Root / NonRoot)
 * - Entitlement Pro
 * - Remote Config (Firebase)
 */
class FeatureGate @Inject constructor(
    private val mode: AppMode,
    private val entitlementManager: EntitlementManager,
    private val remoteConfig: FirebaseRemoteConfig
) {

    // Cache local para evitar lecturas repetidas de RemoteConfig
    private val remoteConfigCache = mutableMapOf<String, Any>()

    /**
     * Verifica si una feature está disponible en el contexto actual.
     */
    fun isEnabled(feature: Feature): Boolean = when (feature) {
        // Core - siempre disponibles
        Feature.SCAN, Feature.VAULT, Feature.PRIVACY -> true

        // Condicionales por Remote Config (features base)
        Feature.NETWORK_MONITOR -> getRemoteConfigBoolean("feature_network_monitor", true)
        Feature.APK_ANALYZER -> getRemoteConfigBoolean("feature_apk_analyzer", true)
        Feature.SCAM_DETECTION -> getRemoteConfigBoolean("feature_scam_detection", true)

        // Condicionales por Modo (Root vs NonRoot)
        Feature.RESTRICT_BACKGROUND -> mode is AppMode.Root
        Feature.DNS_WITHOUT_ADB -> mode is AppMode.Root
        Feature.SYSTEM_APP_SCAN -> mode is AppMode.Root

        // Condicionales por Modo + Pro
        Feature.BEHAVIOR_ANALYSIS -> (mode is AppMode.Root) && entitlementManager.isPro

        // Pro only
        Feature.CLOUD_SYNC -> entitlementManager.isPro
        Feature.EXTRA_THEMES -> entitlementManager.isPro
        Feature.EXPORT_CSV -> entitlementManager.isPro
        Feature.UNLIMITED_HISTORY -> entitlementManager.isPro
        Feature.PRIORITY_SUPPORT -> entitlementManager.isPro
        Feature.BEHAVIOR_ANALYSIS_SANDBOX -> entitlementManager.isPro && (mode is AppMode.Root)

        else -> false
    }

    /**
     * Obtiene un booleano de Remote Config con cache local.
     */
    private fun getRemoteConfigBoolean(key: String, defaultValue: Boolean): Boolean {
        return remoteConfigCache.getOrPut(key) {
            try {
                remoteConfig.getBoolean(key)
            } catch (_: Exception) {
                defaultValue
            }
        }
    } as Boolean

    /**
     * Fuerza refresh del cache de Remote Config (llamar tras fetchAndActivate).
     */
    fun refreshRemoteConfigCache() {
        remoteConfigCache.clear()
    }

    companion object {
        @Volatile private var INSTANCE: FeatureGate? = null

        fun getInstance(context: Context): FeatureGate = INSTANCE ?: synchronized(this) {
            INSTANCE ?: FeatureGate(
                mode = ModeDetector.detect(context.applicationContext),
                entitlementManager = EntitlementManager.getInstance(context),
                remoteConfig = FirebaseRemoteConfig.getInstance()
            ).also { INSTANCE = it }
        }
}