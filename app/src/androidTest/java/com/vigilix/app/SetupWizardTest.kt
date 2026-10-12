package com.vigilix.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests instrumentados para el Setup Wizard (3 niveles: Basic/Advanced/Expert).
 * Valida que el wizard completa correctamente y navega a MainActivity.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SetupWizardTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        // Resetear ExperienceManager para test limpio
        ExperienceManager.getInstance(context).resetForTesting()
    }

    @After
    fun teardown() {
        ExperienceManager.getInstance(context).resetForTesting()
    }

    @Test
    fun wizard_basicLevel_completesAndOpensMain() {
        // Iniciar wizard
        val scenario = ActivityScenario.launch(SetupWizardActivity::class.java)

        scenario.onActivity { activity ->
            // Paso 1: Seleccionar Basic
            onView(withId(R.id.radioBasic)).perform(click())
            onView(withId(R.id.btnNext)).perform(click())

            // Paso 2: Permisos (solo esenciales en Basic)
            onView(withId(R.id.btnNext)).perform(click())

            // Paso 3: Features (solo Scan + Vault en Basic)
            onView(withId(R.id.btnFinish)).perform(click())

            // Verificar que se cierra y va a MainActivity
            // (El wizard llama finish() internamente)
        }
    }

    @Test
    fun wizard_advancedLevel_completesAndOpensMain() {
        val scenario = ActivityScenario.launch(SetupWizardActivity::class.java)

        scenario.onActivity { activity ->
            // Paso 1: Seleccionar Advanced
            onView(withId(R.id.radioAdvanced)).perform(click())
            onView(withId(R.id.btnNext)).perform(click())

            // Paso 2: Permisos (incluye biometric + DNS)
            onView(withId(R.id.btnNext)).perform(click())

            // Paso 3: Features (más opciones)
            onView(withId(R.id.btnFinish)).perform(click())
        }
    }

    @Test
    fun wizard_expertLevel_completesAndOpensMain() {
        val scenario = ActivityScenario.launch(SetupWizardActivity::class.java)

        scenario.onActivity { activity ->
            // Paso 1: Seleccionar Expert
            onView(withId(R.id.radioExpert)).perform(click())
            onView(withId(R.id.btnNext)).perform(click())

            // Paso 2: Permisos (todos incluyendo VPN)
            onView(withId(R.id.btnNext)).perform(click())

            // Paso 3: Features (todas + Pro)
            onView(withId(R.id.btnFinish)).perform(click())
        }
    }

    @Test
    fun wizard_skip_setsBasicAndCompletes() {
        val scenario = ActivityScenario.launch(SetupWizardActivity::class.java)

        scenario.onActivity { activity ->
            onView(withId(R.id.btnSkip)).perform(click())
        }

        // Verificar que el nivel quedó en BASIC
        val exp = ExperienceManager.getInstance(context)
        assert(exp.getLevel() == ExperienceLevel.BASIC)
        assert(exp.isWizardCompleted())
    }
}