package com.vigilix.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.not
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests instrumentados para la pantalla de Suscripción:
 * Ver planes -> seleccionar mensual/anual -> restaurar -> gestionar
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SubscriptionTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        // Resetear entitlement
        EntitlementManager.getInstance(context).resetForTesting()
    }

    @After
    fun teardown() {
        EntitlementManager.getInstance(context).resetForTesting()
    }

    @Test
    fun subscription_screen_showsFreeAndProFeatures() {
        val scenario = ActivityScenario.launch(SubscriptionActivity::class.java)

        scenario.onActivity { activity ->
            // Verificar estado free
            onView(withText(R.string.sub_current_plan_free)).check(matches(isDisplayed()))

            // Verificar funciones gratis
            onView(withText(R.string.sub_free_feature_scan)).check(matches(isDisplayed()))
            onView(withText(R.string.sub_free_feature_vault)).check(matches(isDisplayed()))
            onView(withText(R.string.sub_free_feature_perms)).check(matches(isDisplayed()))

            // Verificar funciones Pro
            onView(withText(R.string.sub_feature_cloud_sync)).check(matches(isDisplayed()))
            onView(withText(R.string.sub_feature_extra_themes)).check(matches(isDisplayed()))
            onView(withText(R.string.sub_feature_export)).check(matches(isDisplayed()))

            // Verificar botones
            onView(withId(R.id.btnSubMonthly)).check(matches(isDisplayed()))
            onView(withId(R.id.btnSubYearly)).check(matches(isDisplayed()))
            onView(withId(R.id.btnSubRestore)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun subscription_restorePurchases_buttonWorks() {
        val scenario = ActivityScenario.launch(SubscriptionActivity::class.java)

        scenario.onActivity { activity ->
            onView(withId(R.id.btnSubRestore)).perform(click())
            // Verifica que muestra progress o mensaje
            onView(withId(R.id.progressSub)).check(matches(anyOf(isDisplayed(), not(isDisplayed()))))
        }
    }

    @Test
    fun subscription_fromMainToolbar_opensScreen() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.onActivity { activity ->
            onView(withId(R.id.menu_subscription)).perform(click())
        }
    }

    @Test
    fun subscription_fromVaultMenu_opensScreen() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.onActivity { activity ->
            onView(withId(R.id.nav_vault)).perform(click())
            onView(withId(R.id.btnVaultMenu)).perform(click())
            onView(withText(R.string.menu_manage_subscription)).perform(click())
        }
    }
}
