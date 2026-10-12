package com.vigilix.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests instrumentados para FeatureGate:
 * Root mode -> features enabled | NonRoot mode -> features gated
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class FeatureGateTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        FeatureGate.getInstance(context).resetForTesting()
    }

    @After
    fun teardown() {
        FeatureGate.getInstance(context).resetForTesting()
    }

    @Test
    fun featureGate_nonRootMode_coreFeaturesEnabled() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.onActivity { activity ->
            val gate = FeatureGate.getInstance(context)
            // Core features siempre disponibles
            assert(gate.isEnabled(Feature.SCAN))
            assert(gate.isEnabled(Feature.VAULT))
            assert(gate.isEnabled(Feature.PRIVACY))

            // Pro features no disponibles sin entitlement
            assert(!gate.isEnabled(Feature.CLOUD_SYNC))
            assert(!gate.isEnabled(Feature.EXTRA_THEMES))
            assert(!gate.isEnabled(Feature.EXPORT_CSV))
        }
    }

    @Test
    fun featureGate_remoteConfigTogglesWork() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.onActivity { activity ->
            val gate = FeatureGate.getInstance(context)
            // Features controladas por Remote Config
            // Por defecto deberían estar habilitadas
            assert(gate.isEnabled(Feature.NETWORK_MONITOR))
            assert(gate.isEnabled(Feature.APK_ANALYZER))
            assert(gate.isEnabled(Feature.SCAM_DETECTION))
        }
    }
}
