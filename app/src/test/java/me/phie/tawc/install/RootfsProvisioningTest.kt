package me.phie.tawc.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * [RootfsProvisioning]: the machine-id validator, the generated id, and
 * the shell it writes the rootfs with — the shell is generated from
 * Kotlin, so escaping bugs are real and are caught here by running it.
 *
 * The script touches only paths under the temporary rootfs it is given,
 * so running it on the build host is safe; the hosts this project builds
 * on are Linux (see notes/building.md), and the assumption keeps the
 * suite honest if that ever changes.
 */
class RootfsProvisioningTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A syntactically valid rootfs: the script assumes /etc exists. */
    private fun syntheticRootfs(): File =
        tmp.newFolder("rootfs").also { File(it, "etc").mkdirs() }

    private fun runScript(rootfs: File, machineId: String = RootfsProvisioning.newMachineId()) {
        assumeTrue("needs /bin/sh", File("/bin/sh").exists())
        val script = RootfsProvisioning.provisioningScript(rootfs.absolutePath, machineId)
        val proc = ProcessBuilder("/bin/sh", "-c", script)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.readBytes().decodeToString()
        assertEquals("provisioning script failed: $output", 0, proc.waitFor())
    }

    private fun machineIdOf(rootfs: File): String =
        File(rootfs, "etc/machine-id").readText().trim()

    private fun perms(file: File): Set<PosixFilePermission> =
        Files.getPosixFilePermissions(file.toPath())

    // --- pure helpers ------------------------------------------------

    @Test
    fun generatedIdIsThirtyTwoLowercaseHex() {
        repeat(16) {
            val id = RootfsProvisioning.newMachineId()
            assertEquals("$id is not 32 chars", 32, id.length)
            assertTrue("$id is not lowercase hex", id.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun machineIdValidation() {
        val dir = tmp.newFolder()
        fun check(name: String, content: String?, expected: Boolean) {
            val f = File(dir, name)
            if (content != null) f.writeText(content)
            assertEquals(name, expected, RootfsProvisioning.isMachineIdValid(f))
        }
        check("valid", "0123456789abcdef0123456789abcdef", true)
        check("trailing-newline", "0123456789abcdef0123456789abcdef\n", true)
        check("uppercase", "0123456789ABCDEF0123456789ABCDEF", false)
        check("short", "0123456789abcdef", false)
        check("empty", "", false)
        // systemd's own placeholder for "not set yet".
        check("uninitialized", "uninitialized\n", false)
        check("missing", null, false)
    }

    // --- the generated shell ----------------------------------------

    @Test
    fun scriptProvisionsAFreshRootfs() {
        val rootfs = syntheticRootfs()
        runScript(rootfs)

        val id = machineIdOf(rootfs)
        assertEquals(32, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
        assertEquals(
            "machine-id must be world-readable, like a real one",
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            ),
            perms(File(rootfs, "etc/machine-id")),
        )

        val runtimeDir = File(rootfs, "run/tawc-runtime")
        assertTrue("runtime dir missing", runtimeDir.isDirectory)
        assertEquals(
            "runtime dir must be private",
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            perms(runtimeDir),
        )
        assertFalse("temp file left behind", File(rootfs, "etc/machine-id.tawc-new").exists())
    }

    @Test
    fun scriptKeepsAValidMachineId() {
        val rootfs = syntheticRootfs()
        File(rootfs, "etc/machine-id").writeText("0123456789abcdef0123456789abcdef\n")
        runScript(rootfs)
        assertEquals("0123456789abcdef0123456789abcdef", machineIdOf(rootfs))
    }

    @Test
    fun scriptRepairsAZeroLengthMachineId() {
        // What ALARM actually ships, and what `dbus-uuidgen --ensure`
        // refuses to repair.
        val rootfs = syntheticRootfs()
        File(rootfs, "etc/machine-id").writeText("")
        runScript(rootfs)
        assertEquals(32, machineIdOf(rootfs).length)
    }

    @Test
    fun scriptRepairsTheUninitializedPlaceholder() {
        val rootfs = syntheticRootfs()
        File(rootfs, "etc/machine-id").writeText("uninitialized\n")
        runScript(rootfs)
        assertTrue(machineIdOf(rootfs).all { it in "0123456789abcdef" })
    }

    @Test
    fun scriptWritesThroughASymlinkedMachineId() {
        // Debian and ALARM disagree about which of /etc/machine-id and
        // /var/lib/dbus/machine-id is the real file; replacing the link
        // would leave the other one empty.
        val rootfs = syntheticRootfs()
        File(rootfs, "var/lib/dbus").mkdirs()
        val target = File(rootfs, "var/lib/dbus/machine-id")
        target.writeText("")
        assertTrue(
            "symlink setup failed",
            File(rootfs, "etc/machine-id").toPath().let {
                Files.deleteIfExists(it)
                Files.createSymbolicLink(it, java.nio.file.Paths.get("../var/lib/dbus/machine-id"))
                Files.isSymbolicLink(it)
            },
        )

        runScript(rootfs)

        assertTrue(
            "/etc/machine-id must stay a symlink",
            Files.isSymbolicLink(File(rootfs, "etc/machine-id").toPath()),
        )
        assertEquals(32, target.readText().trim().length)
    }

    @Test
    fun scriptIsIdempotentAcrossRuns() {
        val rootfs = syntheticRootfs()
        runScript(rootfs)
        val first = machineIdOf(rootfs)
        runScript(rootfs)
        runScript(rootfs)
        assertEquals("a valid id must survive later runs", first, machineIdOf(rootfs))
    }
}
