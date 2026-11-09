package cafe.kankipro.warp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import cafe.kankipro.warp.core.IpTest
import cafe.kankipro.warp.core.Transport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The dialogs open and settle (no endless recomposition). No pictures: with Robolectric's native
 * graphics a dialog never goes idle in the test, though it does on a phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DialogProbeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun pin() {
        rule.setContent { KankiTheme { PinDialog(Transport.GOOL, "162.159.198.2:443", {}) {} } }
        rule.onNodeWithText("آی‌پی ثابت · Gool").assertIsDisplayed()
    }

    @Test fun import() {
        rule.setContent { KankiTheme { ImportDialog(IpTest.Kind.H2, {}) { _, _ -> } } }
        rule.onNodeWithText("تست و ذخیره").assertIsDisplayed()
    }
}
