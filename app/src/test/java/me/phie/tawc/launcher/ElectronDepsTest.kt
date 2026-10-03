package me.phie.tawc.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parsing and package mapping behind the launch-time missing-library
 * warning. Both fail silently — a wrong soname parses to nothing, a wrong
 * package name installs nothing — so the shapes are pinned here instead of
 * being eyeballed on a device.
 */
class ElectronDepsTest {

    private val codiumLdd = """
        	linux-vdso.so.1 (0x0000ffff8c7f0000)
        	libnspr4.so => not found
        	libnss3.so => /usr/lib/libnss3.so (0x0000ffff8c500000)
        	libgtk-3.so.0 => not found
        	libc.so.6 => /usr/lib/libc.so.6 (0x0000ffff8c300000)
        	/usr/lib/ld-linux-aarch64.so.1 (0x0000ffff8c7d0000)
    """.trimIndent()

    @Test
    fun dependenciesAndMissingAreReadFromLddOutput() {
        assertEquals(
            listOf(
                "linux-vdso.so.1",
                "libnspr4.so",
                "libnss3.so",
                "libgtk-3.so.0",
                "libc.so.6",
                "ld-linux-aarch64.so.1",
            ),
            ElectronDeps.dependencies(codiumLdd),
        )
        assertEquals(listOf("libnspr4.so", "libgtk-3.so.0"), ElectronDeps.missingLibs(codiumLdd))
    }

    @Test
    fun electronIsRecognisedByItsNssDependencies() {
        assertTrue(ElectronDeps.looksLikeElectron(ElectronDeps.dependencies(codiumLdd)))
        assertFalse(ElectronDeps.looksLikeElectron(listOf("libc.so.6", "libgtk-3.so.0")))
    }

    @Test
    fun missingLibrariesBecomePackagesPerFamily() {
        val arch = ElectronDeps.report(codiumLdd, "arch")
        assertEquals(listOf("gtk3", "nspr"), arch.packages)
        assertEquals("pacman -S --needed gtk3 nspr", arch.command)
        assertTrue(arch.electron)
        assertTrue(arch.worthWarning)

        val debian = ElectronDeps.report(codiumLdd, "debian-sid")
        assertEquals(listOf("libgtk-3-0", "libnspr4"), debian.packages)
        assertEquals("apt-get install -y libgtk-3-0 libnspr4", debian.command)

        val void = ElectronDeps.report(codiumLdd, "void")
        assertEquals(listOf("gtk+3", "nspr"), void.packages)
        assertEquals("xbps-install -Sy gtk+3 nspr", void.command)
    }

    @Test
    fun unknownSonameIsReportedRatherThanGuessed() {
        val output = "\tlibwho-knows.so.4 => not found\n"
        val report = ElectronDeps.report(output, "arch")
        assertEquals(listOf("libwho-knows.so.4"), report.missing)
        assertEquals(emptyList<String>(), report.packages)
        assertEquals(listOf("libwho-knows.so.4"), report.unknown)
        assertNull(report.command)
        // Missing something is still worth saying out loud.
        assertTrue(report.worthWarning)
    }

    @Test
    fun unknownDistroWithholdsTheCommand() {
        val report = ElectronDeps.report(codiumLdd, "plan9")
        assertEquals(emptyList<String>(), report.packages)
        assertEquals(listOf("libnspr4.so", "libgtk-3.so.0"), report.unknown)
        assertNull(report.command)
        assertNull(ElectronDeps.familyOf("plan9"))
    }

    @Test
    fun nonElfTargetsReportNothing() {
        // A wrapper script (VSCodium ships one) is not a dynamic object.
        val script = "\tnot a dynamic executable\n"
        assertEquals(emptyList<String>(), ElectronDeps.dependencies(script))
        assertEquals(emptyList<String>(), ElectronDeps.missingLibs(script))
        assertFalse(ElectronDeps.report(script, "arch").worthWarning)
    }

    @Test
    fun executableIsTakenFromARealDesktopExecLine() {
        assertEquals(
            "/usr/bin/codium",
            ElectronDeps.executableOf("env BAMF_DESKTOP_FILE_HINT=/x/codium.desktop /usr/bin/codium %U"),
        )
        assertEquals("/opt/vscodium/bin/codium", ElectronDeps.executableOf("\"/opt/vscodium/bin/codium\" --unity-launch"))
        assertEquals("codium", ElectronDeps.executableOf("codium"))
    }

    @Test
    fun probeUsesTheResolvedBinaryAndQuotesIt() {
        val script = ElectronDeps.probeScript("env FOO=bar codium %U")
        assertTrue(script, script.contains("binary='codium'"))
        assertTrue(script, script.contains("command -v"))
        assertTrue(script, script.contains("ldd"))
    }
}
