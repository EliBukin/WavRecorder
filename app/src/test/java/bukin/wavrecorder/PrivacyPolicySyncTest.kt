package bukin.wavrecorder

import androidx.core.text.HtmlCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The privacy policy exists three times -- docs/privacy-policy.md, the static
 * docs/privacy-policy.html generated from it, and the in-app copy (privacy_policy_html) -- and
 * the three must say the same: the same effective date, and the sentences about when recordings
 * leave the phone and what Send feedback does.
 */
@RunWith(RobolectricTestRunner::class)
class PrivacyPolicySyncTest {

    // Unit tests run in the module directory.
    private val markdown = File("../docs/privacy-policy.md").readText()
    private val html = File("../docs/privacy-policy.html").readText()

    private fun inApp(): String = HtmlCompat.fromHtml(
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.privacy_policy_html, "bukin.wavrecorder"),
        HtmlCompat.FROM_HTML_MODE_LEGACY
    ).toString()

    /** Plain words only: no Markdown emphasis, HTML tags or entities, and single spaces. */
    private fun plain(text: String): String = text
        .replace(Regex("<[^>]+>"), "")
        .replace("&#x27;", "'").replace("&quot;", "\"").replace("&amp;", "&")
        .replace("**", "")
        .replace(Regex("\\s+"), " ")

    @Test
    fun `all three copies share the effective date`() {
        val date = Regex("""\*\*Effective date:\*\* (\d{4}-\d{2}-\d{2})""").find(markdown)!!.groupValues[1]
        assertEquals("2026-10-23", date)
        assertTrue(plain(html), plain(html).contains("Effective date: $date"))
        assertTrue(inApp(), inApp().contains("Effective date: $date"))
    }

    @Test
    fun `all three copies say when recordings leave the phone, and what Send feedback does`() {
        val sentences = listOf(
            "Recordings leave your phone through this app only when you use Share or Save a copy, " +
                "or when you pick a cloud-backed folder to save them in.",
            "If you use Send feedback, the app opens your email app with a message to the developer " +
                "that includes the app version, Android version and phone model.",
            "Nothing is sent unless you send it yourself",
        )
        for ((name, copy) in listOf("markdown" to plain(markdown), "html" to plain(html), "in-app" to plain(inApp()))) {
            for (sentence in sentences) assertTrue("$name copy: $sentence", copy.contains(sentence))
        }
    }
}
