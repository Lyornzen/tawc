package me.phie.tawc.install

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import me.phie.tawc.R
import me.phie.tawc.install.distro.Distro
import me.phie.tawc.install.distro.DistroRegistry
import java.util.concurrent.Executor

/**
 * Per-install package-mirror row for the Settings distro card: shows the
 * selected region ([Installation.mirrorRegion]) and opens a chooser over
 * the distro's built-in presets (`Distro.mirrorRegions`), plus a
 * "Default" entry that clears the choice.
 *
 * Picking a region rewrites the installed rootfs's mirror config as well
 * as the metadata. `Distro.configure` only ever runs during an install
 * (notes/installation.md "Upgrade policy"), so without that second step
 * the setting would look applied while every `pacman -S` kept using the
 * old list. The rewrite happens first and the choice is persisted only
 * on success — a region recorded but not applied is the misleading
 * state, and the row then keeps showing what the rootfs actually has.
 *
 * Rows only make sense for distros with a non-empty
 * [Distro.mirrorRegions]; the caller mounts nothing otherwise. Pacman's
 * mirrorlist is the only config a preset rewrites today — distros whose
 * repository path carries release policy (Manjaro ARM) or is already a
 * geo-routed CDN (Debian, Void) declare no regions.
 *
 * [executor] must be single-threaded so rapid picks apply in click order.
 */
internal fun buildMirrorRegionRow(
    activity: Activity,
    store: InstallationStore,
    installation: Installation,
    executor: Executor,
): View {
    val distro = DistroRegistry.forInstallation(installation)
    val regions = distro?.mirrorRegions.orEmpty()

    // Index 0 is "Default" (no region); the rest are the distro's
    // presets, in declaration order.
    val ids: List<String?> = listOf(null) + regions.map { it.id }
    fun labelFor(id: String?): String =
        regions.firstOrNull { it.id == id }?.label
            ?: activity.getString(R.string.settings_mirror_region_default)

    val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val value = TextView(activity).apply {
        text = activity.getString(R.string.settings_mirror_region, labelFor(installation.mirrorRegion))
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
    }
    container.addView(value, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    container.addView(
        TextView(activity).apply {
            text = activity.getString(R.string.settings_mirror_region_detail)
            textSize = 12f
            alpha = 0.7f
        },
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
    )
    if (distro == null || regions.isEmpty()) return container

    container.setOnClickListener {
        val checked = ids.indexOf(installation.mirrorRegion).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_mirror_region_title)
            .setSingleChoiceItems(ids.map { labelFor(it) }.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val picked = ids[which]
                if (picked == installation.mirrorRegion) return@setSingleChoiceItems
                executor.execute {
                    val error = applyMirrorRegion(activity, store, installation, distro, picked)
                    activity.runOnUiThread {
                        if (error == null) {
                            value.text = activity.getString(
                                R.string.settings_mirror_region,
                                labelFor(picked),
                            )
                        } else {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.settings_mirror_region_failed, error),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
            .show()
    }
    return container
}

/**
 * Rewrite [installation]'s mirror config for [regionId] and persist the
 * choice. Returns null on success, else a message for the user.
 *
 * The state gate is re-checked inside the store's update (same rule as
 * [buildAndoCommitRow]): a slot that slipped into INSTALLING or
 * UNINSTALLING under us must not have its rootfs rewritten.
 */
private fun applyMirrorRegion(
    activity: Activity,
    store: InstallationStore,
    installation: Installation,
    distro: Distro,
    regionId: String?,
): String? {
    val method = InstallationMethod.forKey(activity, installation.method)
        ?: return activity.getString(R.string.settings_mirror_region_no_method, installation.method)
    val rootfs = store.rootfsDir(installation.id).absolutePath
    try {
        distro.configureMirrors(method, rootfs, regionId) { }
    } catch (e: Exception) {
        return e.message ?: e.javaClass.simpleName
    }
    val saved = store.update(installation.id) { current ->
        if (current.state == Installation.State.READY || current.state == Installation.State.FAILED) {
            current.copy(mirrorRegion = regionId)
        } else {
            null
        }
    }
    return if (saved == null) activity.getString(R.string.settings_mirror_region_stale) else null
}
