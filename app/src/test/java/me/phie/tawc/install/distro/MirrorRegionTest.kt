package me.phie.tawc.install.distro

import me.phie.tawc.install.distro.arch.ArchLinuxArm
import me.phie.tawc.install.distro.arch.ArchLinuxX86_64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror presets and their rendering.
 *
 * The load-bearing case is [defaultListIsUnchanged]: an install whose
 * user never opened the setting must write exactly the mirrorlist it
 * wrote before the setting existed, byte for byte.
 */
class MirrorRegionTest {

    @Test
    fun defaultListIsUnchanged() {
        // Verbatim copy of the pre-setting ArchLinuxArm.MIRROR_LIST.
        val expected = listOf(
            "Server = https://fl.us.mirror.archlinuxarm.org/\$arch/\$repo",
            "Server = https://ca.us.mirror.archlinuxarm.org/\$arch/\$repo",
            "Server = http://mirror.archlinuxarm.org/\$arch/\$repo",
            "Server = http://nj.us.mirror.archlinuxarm.org/\$arch/\$repo",
            "Server = http://de.mirror.archlinuxarm.org/\$arch/\$repo",
            "Server = http://fr.mirror.archlinuxarm.org/\$arch/\$repo",
        ).joinToString("\n")
        assertEquals(expected, ArchLinuxArm.mirrorConfig(null))
        assertEquals(
            "Server = https://geo.mirror.pkgbuild.com/\$repo/os/\$arch",
            ArchLinuxX86_64.mirrorConfig(null),
        )
    }

    @Test
    fun unknownRegionFallsBackToTheDefault() {
        // Metadata written by a newer build (or a preset we removed)
        // must configure the default list, not fail the install.
        assertNull(ArchLinuxArm.resolveMirrorRegion("no-such-region"))
        assertNull(ArchLinuxArm.resolveMirrorRegion(null))
        assertEquals(ArchLinuxArm.mirrorConfig(null), ArchLinuxArm.mirrorConfig(
            ArchLinuxArm.resolveMirrorRegion("no-such-region"),
        ))
    }

    @Test
    fun knownRegionResolves() {
        val cn = ArchLinuxArm.resolveMirrorRegion("cn")
        assertSame(MirrorRegions.archLinuxArm.first { it.id == "cn" }, cn)
        assertTrue(cn!!.servers.isNotEmpty())
    }

    @Test
    fun regionRendersItsOwnServersInOrder() {
        val region = MirrorRegion("test", "Test", listOf("https://a.example", "https://b.example"))
        assertEquals(
            "Server = https://a.example/\$arch/\$repo\nServer = https://b.example/\$arch/\$repo",
            ArchLinuxArm.mirrorConfig(region),
        )
        assertEquals(
            "Server = https://a.example/\$repo/os/\$arch\nServer = https://b.example/\$repo/os/\$arch",
            ArchLinuxX86_64.mirrorConfig(region),
        )
    }

    @Test
    fun presetsAreWellFormed() {
        for ((name, regions) in listOf(
            "archLinuxArm" to MirrorRegions.archLinuxArm,
            "archLinux" to MirrorRegions.archLinux,
        )) {
            assertTrue("$name has no presets", regions.isNotEmpty())
            assertEquals(
                "$name has duplicate ids",
                regions.size,
                regions.map { it.id }.toSet().size,
            )
            for (region in regions) {
                assertTrue("$name/${region.id} has a blank label", region.label.isNotBlank())
                assertTrue("$name/${region.id} has no servers", region.servers.isNotEmpty())
                for (server in region.servers) {
                    // pacman mirrorlists take a bare base URL; a scheme
                    // typo would only surface as a 404 on-device.
                    assertTrue("$name/${region.id}: bad server $server",
                        server.startsWith("https://") || server.startsWith("http://"))
                    assertTrue("$name/${region.id}: trailing slash in $server",
                        !server.endsWith("/"))
                }
            }
        }
    }

    @Test
    fun distrosWithoutPresetsHaveNone() {
        // Manjaro ARM's paths carry release policy, Debian/Void sit on
        // geo-routed CDNs; all three must keep the settings row hidden.
        for (distro in DistroRegistry.all.filter { it.key != "arch" }) {
            assertTrue("${distro.key} unexpectedly offers mirror presets", distro.mirrorRegions.isEmpty())
        }
    }
}
