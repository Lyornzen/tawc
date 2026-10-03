package me.phie.tawc.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.phie.tawc.R

/**
 * Launch-time "this app needs libraries the distro does not have" dialog.
 *
 * The check runs in a fire-and-forget coroutine ([EntryLauncher]) and the
 * launching screen may already be gone, so the result is delivered by
 * starting this transparent activity from the application context — same
 * shape as [LaunchErrorActivity], which cannot be reused because this
 * dialog has an action (copy the install command) and a way through
 * (launch anyway, past the check).
 */
class MissingDepsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = intent.getStringExtra(EXTRA_COMMAND)
        val installId = intent.getStringExtra(EXTRA_INSTALL_ID)
        val exec = intent.getStringExtra(EXTRA_EXEC)
        val label = intent.getStringExtra(EXTRA_LABEL)

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(intent.getStringExtra(EXTRA_TITLE) ?: "")
            .setMessage(intent.getStringExtra(EXTRA_MESSAGE) ?: "")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.launcher_deps_launch_anyway) { _, _ ->
                if (installId != null && exec != null && label != null) {
                    EntryLauncher.launchSkippingChecks(applicationContext, installId, exec, label)
                }
            }
        if (!command.isNullOrBlank()) {
            builder.setNeutralButton(R.string.launcher_deps_copy) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("tawc install", command))
                Toast.makeText(this, R.string.launcher_deps_copied, Toast.LENGTH_SHORT).show()
            }
        }
        val dialog = builder
            .setOnDismissListener { finish() }
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_COMMAND = "command"
        private const val EXTRA_INSTALL_ID = "installId"
        private const val EXTRA_EXEC = "exec"
        private const val EXTRA_LABEL = "label"

        fun start(
            context: Context,
            title: String,
            message: String,
            command: String?,
            installId: String,
            exec: String,
            label: String,
        ) {
            context.startActivity(
                Intent(context, MissingDepsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_MESSAGE, message)
                    .putExtra(EXTRA_COMMAND, command)
                    .putExtra(EXTRA_INSTALL_ID, installId)
                    .putExtra(EXTRA_EXEC, exec)
                    .putExtra(EXTRA_LABEL, label),
            )
        }
    }
}
