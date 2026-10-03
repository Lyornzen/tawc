package me.phie.tawc.install

import me.phie.tawc.install.distro.Distro
import me.phie.tawc.install.distro.DistroRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AUR-build recipe. The script runs inside a guest we cannot reach from
 * a unit test, so what is pinned here is the part that would fail silently:
 * the status tokens the row parses, and the recipe carrying the pinned
 * digest and the TCP IPC flag it exists for.
 */
class SysvIpcFixTest {

    private fun distro(key: String): Distro =
        DistroRegistry.all.first { it.key == key }

    @Test
    fun statusTokensRoundTrip() {
        assertEquals(SysvIpcFix.Status.TCP, SysvIpcFix.parseStatus(SysvIpcFix.STATUS_TCP + "\n"))
        assertEquals(SysvIpcFix.Status.BROKEN, SysvIpcFix.parseStatus("noise\n" + SysvIpcFix.STATUS_BROKEN))
        assertEquals(SysvIpcFix.Status.MISSING, SysvIpcFix.parseStatus(SysvIpcFix.STATUS_MISSING))
        assertEquals(SysvIpcFix.Status.OK, SysvIpcFix.parseStatus(SysvIpcFix.STATUS_OK))
        assertEquals(SysvIpcFix.Status.UNKNOWN, SysvIpcFix.parseStatus(""))
        assertEquals(SysvIpcFix.Status.UNKNOWN, SysvIpcFix.parseStatus("fakeroot: something new"))
    }

    @Test
    fun prerequisitesArePerPackageManager() {
        assertEquals(
            "pacman -S --needed --noconfirm base-devel curl",
            SysvIpcFix.prerequisites(distro(Installation.DISTRO_ARCH)),
        )
        assertEquals(
            "apt-get install -y --no-install-recommends build-essential curl",
            SysvIpcFix.prerequisites(distro(Installation.DISTRO_DEBIAN_SID)),
        )
        assertEquals(
            "xbps-install -Sy base-devel curl",
            SysvIpcFix.prerequisites(distro(Installation.DISTRO_VOID)),
        )
        assertNull(SysvIpcFix.prerequisites(null))
    }

    @Test
    fun setupBuildsFakerootWithTcpIpcIntoLocalPrefix() {
        val script = SysvIpcFix.setupScript(distro(Installation.DISTRO_ARCH))!!
        assertTrue(script, script.contains("--with-ipc=tcp"))
        assertTrue(script, script.contains("--prefix=${SysvIpcFix.PREFIX}"))
        assertTrue(script, script.contains(SysvIpcFix.TARBALL_SHA256))
        assertTrue(script, script.contains("sha256sum -c -"))
        assertTrue(script, script.contains(SysvIpcFix.MARKER))
        // Idempotent: both "already ours" and "distro's works" exit early.
        assertTrue(script, script.contains("already installed"))
        assertTrue(script, script.contains("nothing to do"))
    }

    @Test
    fun setupIsUnavailableForUnknownDistros() {
        assertNull(SysvIpcFix.setupScript(null))
    }

    @Test
    fun statusProbeDistinguishesOurBuildFromTheDistros() {
        val script = SysvIpcFix.statusScript()
        assertTrue(script, script.contains(SysvIpcFix.PREFIX))
        assertTrue(script, script.contains(SysvIpcFix.STATUS_TCP))
        assertTrue(script, script.contains(SysvIpcFix.STATUS_BROKEN))
        assertTrue(script, script.contains(SysvIpcFix.STATUS_MISSING))
    }
}
