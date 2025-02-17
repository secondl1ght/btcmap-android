package donation

import android.content.ClipboardManager
import android.content.Context
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import org.btcmap.R
import kotlin.test.Test
import kotlin.test.assertEquals

class DonationFragmentTest {

    @Test
    fun launch() {
        launchFragmentInContainer<DonationFragment>(
            themeResId = R.style.Theme_Material3_DynamicColors_DayNight,
        ).use {
            onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
            onView(withId(R.id.message)).check(matches(isDisplayed()))
            onView(withId(R.id.message)).check(matches(withText(R.string.help_us_improve_btc_map)))
            onView(withId(R.id.qr)).check(matches(isDisplayed()))
            onView(withId(R.id.copy)).check(matches(isDisplayed()))
            onView(withId(R.id.copy)).check(matches(isClickable()))

            onView(withId(R.id.copy)).perform(click())
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipboardText = clipManager.primaryClip!!.getItemAt(clipManager.primaryClip!!.itemCount - 1).text
            assertEquals(context.getString(R.string.donation_address), clipboardText)
        }
    }
}