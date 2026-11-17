package com.voxarium.remote.transport

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.voxarium.remote.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** Resource/package policy checks, not a claim of device backup/Keystore verification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransportSecurityTest {
    @Test fun networkPermissionAndBackupPolicyArePackaged() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions?.contains("android.permission.INTERNET") == true)
    }
    @Test fun transferAndBackupExplicitlyExcludeAllStorageDomains() {
        val resources = RuntimeEnvironment.getApplication().resources
        for (id in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val parser = resources.getXml(id)
            parser.use {
                var section = "legacy"
                val exclusions = mutableMapOf<String, MutableSet<String>>()
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        if (parser.name in listOf("cloud-backup", "device-transfer")) section = parser.name
                        if (parser.name == "exclude") {
                            assertEquals(".", parser.getAttributeValue(null, "path"))
                            exclusions.getOrPut(section) { mutableSetOf() }.add(parser.getAttributeValue(null, "domain"))
                        }
                    }
                    parser.next()
                }
                val expected = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
                if (id == R.xml.backup_rules) assertEquals(expected, exclusions["legacy"])
                else {
                    assertEquals(expected, exclusions["cloud-backup"])
                    assertEquals(expected, exclusions["device-transfer"])
                }
            }
        }
    }
    @Test fun securityXmlHasOnlySystemTrustAndNoDebugOverride() {
        val parser = RuntimeEnvironment.getApplication().resources.getXml(R.xml.network_security_config)
        parser.use {
            var anchors = 0
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    assertNotEquals("debug-overrides", parser.name)
                    if (parser.name == "base-config") assertEquals("false", parser.getAttributeValue(null, "cleartextTrafficPermitted"))
                    if (parser.name == "certificates") {
                        anchors++
                        assertEquals("system", parser.getAttributeValue(null, "src"))
                    }
                }
                parser.next()
            }
            assertEquals(1, anchors)
        }
    }
}
