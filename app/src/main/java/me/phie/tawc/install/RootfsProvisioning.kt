package me.phie.tawc.install

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * Files every rootfs needs but no distro bootstrap ships.
 *
 * - **`/etc/machine-id`** — dbus refuses to start a session bus without
 *   it ("Cannot spawn a message bus without a machine-id"), which takes
 *   dconf/GTK settings and every dbus-using app down with it. Arch Linux
 *   ARM ships the file zero-length, so `dbus-uuidgen --ensure` (which
 *   only creates a *missing* file) does not repair it either.
 * - **[RootfsEnv.GUEST_RUNTIME_DIR]** — `XDG_RUNTIME_DIR` has to be a
 *   private directory; dbus checks both the owner and that group/other
 *   have no access. `/tmp` is 1777 and shared, so the session bus never
 *   started.
 *
 * Runs at install time and again on app start for installs that predate
 * this code (the field devices the bug was reported on). Both steps are
 * idempotent and skip the shell entirely once the rootfs is in shape, so
 * the app-start sweep costs a few `stat`s per install.
 *
 * Written through [InstallationMethod.runOutside] rather than Kotlin
 * file I/O: a chroot rootfs is uid-0-owned, and that path is `su`.
 *
 * Deliberately conservative about an existing machine-id: a valid one is
 * never rewritten, because changing it resets D-Bus, at-spi and dconf
 * state for that rootfs.
 */
internal object RootfsProvisioning {
    private const val TAG = "tawc-install"

    /** Relative to the rootfs; also the symlink dbus may point here. */
    private const val MACHINE_ID_PATH = "etc/machine-id"

    /** 32 lowercase hex characters — what `machine-id(5)` requires. */
    private val MACHINE_ID_RE = Regex("[0-9a-f]{32}")

    private val PRIVATE_DIR_PERMS = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

    /**
     * Bring [rootfs] into shape. Returns true when something was
     * written, false when it was already correct. Throws if the write
     * fails, so callers can decide whether that is fatal (it never is —
     * a rootfs without these files still boots, with degraded dbus).
     */
    fun ensure(
        method: InstallationMethod,
        rootfs: String,
        log: ((String) -> Unit)? = null,
    ): Boolean {
        val runtimeDir = File(rootfs, RootfsEnv.GUEST_RUNTIME_DIR.removePrefix("/"))
        val machineId = File(rootfs, MACHINE_ID_PATH)
        if (isPrivateDir(runtimeDir) && isMachineIdValid(machineId)) return false
        val result = method.runOutside(provisioningScript(rootfs, newMachineId()), log)
        if (!result.ok) {
            throw IOException("rootfs provisioning failed (exit ${result.exitCode})")
        }
        return true
    }

    /**
     * Best-effort [ensure] for every installed rootfs, for the app-start
     * heal sweep. Skips installs that are mid-operation (their rootfs is
     * the installer's to touch) and methods this build doesn't ship;
     * per-install failures are logged, never fatal.
     */
    fun ensureAll(context: Context, store: InstallationStore) {
        var updated = 0
        var failed = 0
        for (inst in store.list()) {
            // Same containment rule as the other startup sweeps: never
            // build a path from an unvalidated id.
            if (!Installation.isValidId(inst.id)) continue
            if (inst.state != Installation.State.READY && inst.state != Installation.State.FAILED) continue
            val method = InstallationMethod.forKey(context, inst.method) ?: continue
            try {
                if (ensure(method, store.rootfsDir(inst.id).absolutePath)) updated++
            } catch (t: Throwable) {
                failed++
                Log.w(TAG, "rootfs provisioning failed for ${inst.id}: ${t.message}")
            }
        }
        if (updated > 0 || failed > 0) {
            Log.i(TAG, "rootfs provisioning: updated=$updated failed=$failed")
        }
    }

    /** A fresh machine id: 32 lowercase hex characters, no dashes. */
    internal fun newMachineId(): String = UUID.randomUUID().toString().replace("-", "")

    /** `machine-id(5)`: exactly 32 lowercase hex characters. */
    internal fun isMachineIdValid(file: File): Boolean =
        runCatching { MACHINE_ID_RE.matches(file.readText().trim()) }.getOrDefault(false)

    private fun isPrivateDir(dir: File): Boolean =
        dir.isDirectory && runCatching {
            Files.getPosixFilePermissions(dir.toPath()) == PRIVATE_DIR_PERMS
        }.getOrDefault(false)

    /**
     * The one shell that does both writes. `/etc/machine-id` is written
     * *through* a symlink when one is present — Debian and ALARM
     * disagree about which of `/etc/machine-id` and
     * `/var/lib/dbus/machine-id` is the real file and which is the link,
     * and replacing the link would leave the other one empty. The value
     * is only written when the existing one is missing, empty or
     * malformed (`uninitialized` is systemd's own placeholder), so a
     * valid id survives every run.
     *
     * The temp-file + `mv` keeps the write atomic in the same directory
     * as the target; no deletion is involved (the rootfs-cleaner
     * tripwire forbids delete patterns in shipped code).
     */
    internal fun provisioningScript(rootfs: String, machineId: String): String = buildString {
        appendLine("set -eu")
        appendLine("ROOTFS='$rootfs'")
        appendLine("RT=\"\$ROOTFS${RootfsEnv.GUEST_RUNTIME_DIR}\"")
        appendLine("mkdir -p \"\$RT\"")
        appendLine("chmod 700 \"\$RT\"")
        appendLine("MID=\"\$ROOTFS/$MACHINE_ID_PATH\"")
        appendLine("if [ -L \"\$MID\" ]; then")
        appendLine("    LINK=\$(readlink \"\$MID\")")
        appendLine("    case \"\$LINK\" in")
        appendLine("        /*) MID=\"\$ROOTFS\$LINK\" ;;")
        appendLine("        *) MID=\"\$ROOTFS/etc/\$LINK\" ;;")
        appendLine("    esac")
        appendLine("fi")
        appendLine("if [ ! -s \"\$MID\" ] || ! grep -Eq '^[0-9a-f]{32}\$' \"\$MID\"; then")
        appendLine("    printf '%s\\n' '$machineId' > \"\$MID.tawc-new\"")
        appendLine("    chmod 444 \"\$MID.tawc-new\"")
        appendLine("    mv -f \"\$MID.tawc-new\" \"\$MID\"")
        appendLine("fi")
    }
}
