package com.vigilix.app

import org.junit.Assert.*
import org.junit.Test

class FeatureGateLogicTest {

    @Test
    fun coreFeaturesAlwaysEnabled() {
        // Estas features no dependen de modo ni entitlement
        assertEquals(true, Feature.SCAN.enabledFor(null, false))
        assertEquals(true, Feature.VAULT.enabledFor(null, false))
        assertEquals(true, Feature.PRIVACY.enabledFor(null, false))
    }

    @Test
    fun rootOnlyFeatures() {
        // RESTRICT_BACKGROUND, DNS_WITHOUT_ADB, SYSTEM_APP_SCAN requieren Root
        assertEquals(true, Feature.RESTRICT_BACKGROUND.enabledFor(AppMode.Root(hasMagisk = false, hasKernelSu = false, hasDebug = false), false))
        assertEquals(false, Feature.RESTRICT_BACKGROUND.enabledFor(AppMode.NonRoot(hasDebug = false, hasAdbWireless = false), false))

        assertEquals(true, Feature.DNS_WITHOUT_ADB.enabledFor(AppMode.Root(hasMagisk = false, hasKernelSu = false, hasDebug = false), false))
        assertEquals(false, Feature.DNS_WITHOUT_ADB.enabledFor(AppMode.NonRoot(hasDebug = false, hasAdbWireless = false), false))
    }

    @Test
    fun proOnlyFeatures() {
        // CLOUD_SYNC, EXTRA_THEMES, EXPORT_CSV, etc. requieren Pro
        assertEquals(false, Feature.CLOUD_SYNC.enabledFor(null, false))
        assertEquals(true, Feature.CLOUD_SYNC.enabledFor(null, true))

        assertEquals(false, Feature.EXTRA_THEMES.enabledFor(null, false))
        assertEquals(true, Feature.EXTRA_THEMES.enabledFor(null, true))
    }

    @Test
    fun behaviorAnalysisRequiresRootAndPro() {
        // BEHAVIOR_ANALYSIS requiere Root AND Pro
        assertEquals(false, Feature.BEHAVIOR_ANALYSIS.enabledFor(AppMode.Root(hasMagisk = false, hasKernelSu = false, hasDebug = false), false))
        assertEquals(false, Feature.BEHAVIOR_ANALYSIS.enabledFor(AppMode.NonRoot(hasDebug = false, hasAdbWireless = false), true))
        assertEquals(true, Feature.BEHAVIOR_ANALYSIS.enabledFor(AppMode.Root(hasMagisk = false, hasKernelSu = false, hasDebug = false), true))
    }
}

// Helper para testear Feature sin RemoteConfig
fun Feature.enabledFor(mode: AppMode?, isPro: Boolean): Boolean = when (this) {
    Feature.SCAN, Feature.VAULT, Feature.PRIVACY -> true
    Feature.RESTRICT_BACKGROUND, Feature.DNS_WITHOUT_ADB, Feature.SYSTEM_APP_SCAN -> mode is AppMode.Root
    Feature.BEHAVIOR_ANALYSIS -> (mode is AppMode.Root) && isPro
    Feature.CLOUD_SYNC, Feature.EXTRA_THEMES, Feature.EXPORT_CSV, Feature.UNLIMITED_HISTORY, Feature.PRIORITY_SUPPORT -> isPro
    Feature.NETWORK_MONITOR, Feature.APK_ANALYZER, Feature.SCAM_DETECTION -> true
    Feature.BEHAVIOR_ANALYSIS_SANDBOX -> isPro && (mode is AppMode.Root)
}