package tw.hoonsor.voicehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.backup.AutoBackup
import tw.hoonsor.voicehub.data.SettingsBackup
import java.util.Calendar

class AutoBackupTest {
    @Test fun fileName() {
        val c = Calendar.getInstance().apply { set(2026, 10, 10, 9, 5) }
        assertEquals("VoiceHub-20261110-0905.json", AutoBackup.fileName("VoiceHub", "json", c.time))
    }

    @Test fun onlyOwnFiles() {
        assertTrue(AutoBackup.isBackupName("VoiceHub-20261110-0905.json", "VoiceHub", "json"))
        assertTrue(AutoBackup.isBackupName("VoiceHub-20261110-0905 (1).json", "VoiceHub", "json"))
        assertFalse(AutoBackup.isBackupName("VoiceHub-LAB-20261110-0905.json", "VoiceHub", "json"))
        assertFalse(AutoBackup.isBackupName("VoiceHub-20261110-0905.zip", "VoiceHub", "json"))
        assertFalse(AutoBackup.isBackupName("我的筆記.json", "VoiceHub", "json"))
    }

    @Test fun keepNewest() {
        val names = (1..9).map { "A-2026110$it-1200.zip" } + "notes.txt"
        val del = AutoBackup.toDelete(names, "A", "zip", 7)
        assertEquals(listOf("A-20261102-1200.zip", "A-20261101-1200.zip"), del)
        assertTrue(AutoBackup.toDelete(names.take(7), "A", "zip", 7).isEmpty())
    }
}

class SettingsBackupTest {
    @Test fun roundTrip() {
        val src = mapOf(
            "places" to "家=桃園市…\n公司=…", "autoListen" to true, "briefMinute" to 1260,
            "calWriteId" to 12L, "speechRate" to 1.25f, "briefExcluded" to setOf("3", "1"),
        )
        val back = SettingsBackup.decode(SettingsBackup.encode(src))
        assertEquals(src, back)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherFiles() {
        SettingsBackup.decode(org.json.JSONObject().put("format", "photobox-backup"))
    }
}
