package me.phie.tawc.install

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.phie.tawc.R
import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.ui.tonalButton
import java.util.concurrent.Executor

/**
 * Settings distro-card row that sets AUR builds up for this rootfs: it
 * installs a fakeroot built with TCP IPC, which is the half of System V
 * IPC that Android kernels do not have ([SysvIpcFix] carries the why).
 *
 * A row rather than an automatic install-time step: the fix compiles
 * fakeroot, so it needs the distro's build tools and network, and neither
 * is guaranteed until the user is actually building packages. Tapping it
 * twice is safe — the script exits early when fakeroot already works.
 *
 * The whole operation runs inside the guest through [InstallationMethod],
 * so it works for every install method that has a package manager; the
 * row is only mounted when [SysvIpcFix.prerequisites] knows the family.
 *
 * [executor] must be single-threaded: the status probe and a running setup
 * would otherwise interleave and report each other's state.
 */
internal fun buildAurBuildRow(
    activity: Activity,
    store: InstallationStore,
    installation: Installation,
    executor: Executor,
): View {
    val distro = DistroRegistry.forInstallation(installation)
    val method = InstallationMethod.forKey(activity, installation.method)
    val rootfs = store.rootfsDir(installation.id).absolutePath

    val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val row = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    row.addView(
        TextView(activity).apply {
            text = activity.getString(R.string.aur_build_label)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        },
        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
    )
    val status = TextView(activity).apply {
        text = activity.getString(R.string.aur_build_status_checking)
        textSize = 12f
        alpha = 0.7f
    }

    fun setStatus(text: String) = activity.runOnUiThread { status.text = text }

    fun statusText(reported: SysvIpcFix.Status): String = when (reported) {
        SysvIpcFix.Status.TCP -> activity.getString(R.string.aur_build_status_ready)
        SysvIpcFix.Status.OK -> activity.getString(R.string.aur_build_status_ok)
        SysvIpcFix.Status.BROKEN -> activity.getString(R.string.aur_build_status_broken)
        SysvIpcFix.Status.MISSING -> activity.getString(R.string.aur_build_status_missing)
        SysvIpcFix.Status.UNKNOWN -> activity.getString(R.string.aur_build_status_unknown)
    }

    fun probe() {
        if (method == null) {
            setStatus(activity.getString(R.string.aur_build_status_unknown))
            return
        }
        executor.execute {
            val result = runCatching { method.runInside(rootfs, SysvIpcFix.statusScript()) }
                .getOrNull()
            val reported = result?.let { SysvIpcFix.parseStatus(it.output) } ?: SysvIpcFix.Status.UNKNOWN
            setStatus(statusText(reported))
        }
    }

    fun showLog(title: String, log: String) {
        val view = TextView(activity).apply {
            text = log.ifBlank { activity.getString(R.string.aur_build_log_empty) }
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            val pad = (12 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(activity).apply { addView(view) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun setUp() {
        val script = SysvIpcFix.setupScript(distro)
        if (method == null || script == null) {
            setStatus(activity.getString(R.string.aur_build_status_unknown))
            return
        }
        val progress = MaterialAlertDialogBuilder(activity)
            .setMessage(activity.getString(R.string.aur_build_running))
            .setCancelable(false)
            .show()
        executor.execute {
            val log = StringBuilder()
            val result = runCatching {
                method.runInside(rootfs, script, onLine = { line -> log.appendLine(line) })
            }
            val outcome = result.getOrNull()
            if (result.isFailure) {
                log.appendLine(result.exceptionOrNull()?.message ?: "failed")
            }
            activity.runOnUiThread {
                progress.dismiss()
                showLog(
                    activity.getString(
                        if (outcome?.ok == true) R.string.aur_build_result_ok else R.string.aur_build_result_failed,
                    ),
                    log.toString(),
                )
                probe()
            }
        }
    }

    val button = activity.tonalButton(activity.getString(R.string.aur_build_action_setup)) { setUp() }
    row.addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    container.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    container.addView(
        TextView(activity).apply {
            text = activity.getString(R.string.aur_build_detail)
            textSize = 12f
            alpha = 0.7f
        },
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
    )
    container.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

    // Long-press copies the manual recipe, for users who would rather run it
    // themselves (or whose build tools are missing).
    container.setOnLongClickListener {
        val command = SysvIpcFix.setupScript(distro)
        if (command != null) {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("tawc fakeroot-tcp", command))
            Toast.makeText(activity, R.string.aur_build_recipe_copied, Toast.LENGTH_SHORT).show()
        }
        true
    }

    probe()
    return container
}
