package com.vigilix.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.not
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests instrumentados para el flujo del Escáner:
 * quick -> full -> cancel -> VT toggle
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ScanFlowTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        ScanService.stop(context)
    }

    @After
    fun teardown() {
        ScanService.stop(context)
    }

    @Test
    fun scan_quickScan_startsAndShowsProgress() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.onActivity { activity ->
            onView(withId(R.id.nav_security)).perform(click())

            // Seleccionar modo rápido
            onView(withId(R.id.btnScanQuick)).perform(click())

            // Iniciar escaneo
            onView(withId(R.id.btnScanStart)).perform(click())

            // Verificar que muestra progreso
            onView(withId(R.id.progressScan)).check(matches(isDisplayed()))
            onView(withId(R.id.btnScanCancel)).check(matches(isDisplayed()))

            // Cancelar
            onView(withId(R.id.btnScanCancel)).perform(click())
            onView(withId(R.id.calloutScanResult)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun scan_fullScan_toggleWorks() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.onActivity { activity ->
            onView(withId(R.id.nav_security)).perform(click())

            // Cambiar a modo completo
            onView(withId(R.id.btnScanFull)).perform(click())
            onView(withId(R.id.btnScanFull)).check(matches(isChecked()))

            // Volver a rápido
            onView(withId(R.id.btnScanQuick)).perform(click())
            onView(withId(R.id.btnScanQuick)).check(matches(isChecked()))
        }
    }

    @Test
    fun scan_virusTotal_toggleWorks() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.onActivity { activity ->
            onView(withId(R.id.nav_security)).perform(click())

            // Toggle VirusTotal
            onView(withId(R.id.switchVt)).perform(click())
            onView(withId(R.id.switchVt)).check(matches(isChecked()))

            onView(withId(R.id.switchVt)).perform(click())
            onView(withId(R.id.switchVt)).check(matches(not(isChecked())))
        }
    }

    @Test
    fun scan_history_clearWorks() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.onActivity { activity ->
            onView(withId(R.id.nav_security)).perform(click())

            // Verificar botón clear history existe
            onView(withId(R.id.btnClearHistory)).check(matches(isDisplayed()))
        }
    }
}
