package bukin.wavrecorder

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android backup and device transfer copy only ordinary preferences -- the split length -- and
 * never recordings, the picked folder, crash-recovery records or pending outcomes. The rules are
 * an allowlist, for Android 6-11 (backup_rules.xml) and Android 12+ (data_extraction_rules.xml).
 * This checks the rules themselves, that the manifest uses them, and that they name the
 * preference files the code really writes. (It doesn't run a backup: see
 * docs/play/PUBLICATION-CHECKLIST.md for the emulator check.)
 */
@RunWith(RobolectricTestRunner::class)
class BackupRulesTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private data class Rule(val section: String, val kind: String, val domain: String, val path: String)

    /** Every include/exclude in an XML rules file, with the section it's in. */
    private fun rules(xmlRes: Int): List<Rule> {
        val parser = app().resources.getXml(xmlRes)
        val sections = ArrayDeque<String>()
        val found = mutableListOf<Rule>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "include" || parser.name == "exclude") {
                        found += Rule(
                            sections.last(), parser.name,
                            parser.getAttributeValue(null, "domain"), parser.getAttributeValue(null, "path")
                        )
                    }
                    sections.addLast(parser.name)
                }
                XmlPullParser.END_TAG -> sections.removeLast()
            }
        }
        return found
    }

    /** A preferences file name, read from the class that owns it, so a rename can't go unnoticed. */
    private fun prefsFile(owner: Class<*>): String =
        owner.getDeclaredField("PREFS_NAME").apply { isAccessible = true }.get(null) as String + ".xml"

    private val settingsFile get() = prefsFile(RecordingSettings::class.java)

    @Test
    fun `Android 6 to 11 - only the settings file is backed up`() {
        assertEquals(
            listOf(Rule("full-backup-content", "include", "sharedpref", settingsFile)),
            rules(R.xml.backup_rules)
        )
    }

    @Test
    fun `Android 12+ - only the settings file is backed up to the cloud or moved to a new device`() {
        assertEquals(
            listOf(
                Rule("cloud-backup", "include", "sharedpref", settingsFile),
                Rule("device-transfer", "include", "sharedpref", settingsFile)
            ),
            rules(R.xml.data_extraction_rules)
        )
    }

    @Test
    fun `device-specific state lives in files the rules leave out`() {
        val included = (rules(R.xml.backup_rules) + rules(R.xml.data_extraction_rules)).map { it.path }.toSet()
        listOf(DestinationManager::class.java, ActiveSegmentJournal::class.java, PendingOutcomeStore::class.java).forEach {
            val file = prefsFile(it)
            assertFalse("${it.simpleName}'s $file must not be backed up", file in included)
            assertFalse("${it.simpleName} must not share the settings file", file == settingsFile)
        }
    }

    @Test
    fun `the split length is stored in the file that is backed up`() {
        RecordingSettings(app()).splitDuration = RecordingSplitDuration.entries.last()
        val stored = app().getSharedPreferences(settingsFile.removeSuffix(".xml"), Context.MODE_PRIVATE)
        assertEquals(RecordingSplitDuration.entries.last().minutes, stored.getInt("split_duration_minutes", -1))
    }

    @Test
    fun `the manifest uses both rule files, and no custom backup agent`() {
        // The app's own manifest (unit tests run in the module directory). The manifest AGP hands
        // to unit tests drops the app's <application> attributes, and the final artifact's merged
        // manifest is checked with aapt2 as part of the release checks.
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("true", application.getAttributeNS(android, "allowBackup"))
        assertEquals("@xml/backup_rules", application.getAttributeNS(android, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(android, "dataExtractionRules"))
        // No app code runs during backup or restore, so neither can touch live recovery records.
        assertTrue(application.getAttributeNS(android, "backupAgent").isEmpty())
    }
}
