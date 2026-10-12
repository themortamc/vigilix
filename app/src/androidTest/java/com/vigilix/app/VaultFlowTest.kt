package com.vigilix.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests instrumentados para el flujo de la Bóveda:
 * create -> unlock -> add -> lock -> unlock biometric
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class VaultFlowTest {

    private lateinit var context: Context

    companion object {
        private const val TEST_PASSWORD = "TestPassword123!"
        private const val TEST_PASSWORD2 = "TestPassword456!"
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        VaultStore.lock()
    }

    @After
    fun teardown() {
        VaultStore.lock()
    }

    @Test
    fun vault_createUnlockAddLock_unlockBio() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.onActivity { activity ->
            onView(withId(R.id.nav_vault)).perform(click())

            onView(withId(R.id.etNewPass)).perform(typeText(TEST_PASSWORD), closeSoftKeyboard())
            onView(withId(R.id.etNewPass2)).perform(typeText(TEST_PASSWORD), closeSoftKeyboard())
            onView(withId(R.id.btnCreateVault)).perform(click())

            onView(withId(R.id.panelOpen)).check(matches(isDisplayed()))

            onView(withId(R.id.btnAddItem)).perform(click())
            onView(withId(R.id.etCredTitle)).perform(typeText("Test Credential"), closeSoftKeyboard())
            onView(withId(R.id.etCredUrl)).perform(typeText("https://example.com"), closeSoftKeyboard())
            onView(withId(R.id.etCredUser)).perform(typeText("testuser"), closeSoftKeyboard())
            onView(withId(R.id.etCredPass)).perform(typeText("testpass"), closeSoftKeyboard())
            onView(withId(android.R.id.button1)).perform(click())

            onView(withText("Test Credential")).check(matches(isDisplayed()))

            onView(withId(R.id.btnLockNow)).perform(click())
            onView(withId(R.id.panelLocked)).check(matches(isDisplayed()))

            onView(withId(R.id.etUnlock)).perform(typeText(TEST_PASSWORD), closeSoftKeyboard())
            onView(withId(R.id.btnUnlock)).perform(click())
            onView(withId(R.id.panelOpen)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun vault_changeMasterPassword() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.onActivity { activity ->
            onView(withId(R.id.nav_vault)).perform(click())
            onView(withId(R.id.etNewPass)).perform(typeText(TEST_PASSWORD), closeSoftKeyboard())
            onView(withId(R.id.etNewPass2)).perform(typeText(TEST_PASSWORD), closeSoftKeyboard())
            onView(withId(R.id.btnCreateVault)).perform(click())

            onView(withId(R.id.btnVaultMenu)).perform(click())
            onView(withText(R.string.menu_change_password)).perform(click())

            onView(withId(R.id.etPwCurrent)).perform(typeText(TEST_PASSWORD), closeSoftKeyboard())
            onView(withId(R.id.etPwNew)).perform(typeText(TEST_PASSWORD2), closeSoftKeyboard())
            onView(withId(R.id.etPwNew2)).perform(typeText(TEST_PASSWORD2), closeSoftKeyboard())
            onView(withId(android.R.id.button1)).perform(click())

            onView(withId(R.id.btnLockNow)).perform(click())
            onView(withId(R.id.etUnlock)).perform(typeText(TEST_PASSWORD2), closeSoftKeyboard())
            onView(withId(R.id.btnUnlock)).perform(click())
            onView(withId(R.id.panelOpen)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun vault_exportImportRoundtrip() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.onActivity { activity ->
            onView(withId(R.id.nav_vault)).perform(click())
            onView(withId(R.id.etNewPass)).perform(typeText(TEST_PASSWORD), closeSoftKeyboard())
            onView(withId(R.id.etNewPass2)).perform(typeText(TEST_PASSWORD), closeSoftKeyboard())
            onView(withId(R.id.btnCreateVault)).perform(click())

            onView(withId(R.id.btnAddItem)).perform(click())
            onView(withId(R.id.etCredTitle)).perform(typeText("Export Test"), closeSoftKeyboard())
            onView(withId(R.id.etCredPass)).perform(typeText("secret"), closeSoftKeyboard())
            onView(withId(android.R.id.button1)).perform(click())

            onView(withId(R.id.btnVaultMenu)).perform(click())
            onView(withText(R.string.menu_export)).check(matches(isDisplayed()))
        }
    }
}
