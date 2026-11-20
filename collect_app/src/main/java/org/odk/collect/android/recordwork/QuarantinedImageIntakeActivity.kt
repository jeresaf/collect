package org.odk.collect.android.recordwork

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import org.odk.collect.android.R
import org.odk.collect.android.injection.DaggerUtils
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.strings.localization.LocalizedActivity
import java.util.UUID
import java.util.concurrent.Executors

/**
 * An explicit image picker for the *current* target XForm's non-repeat media
 * fields. No media URI, image bytes or source answers enter an Intent or an
 * ODK Instance. Every open/import/clear rechecks READY server authority.
 *
 * This screen is separate from QuarantinedTextDraftActivity: forms with
 * media/complex fields remain ineligible for the scalar editor. These are
 * staged encrypted assets only, not saved JavaRosa binary answers.
 */
class QuarantinedImageIntakeActivity : LocalizedActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var settings: SettingsProvider
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var rows: LinearLayout
    private var generation = 0
    private var active: QuarantinedWorkDraft? = null
    private var paths = emptyList<String>()
    private var chosenPath: String? = null

    private val projectId get() = intent.getStringExtra(EXTRA_PROJECT).orEmpty()
    private val workId get() = intent.getStringExtra(EXTRA_WORK).orEmpty()

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val field = chosenPath
        chosenPath = null
        if (uri != null && field != null && !isFinishing && !isDestroyed) {
            importSelected(field, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        settings = DaggerUtils.getComponent(this).settingsProvider()
        title = getString(R.string.private_image_title)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        status = TextView(this).apply {
            setText(R.string.private_image_loading)
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(status)
        content.addView(progress)
        content.addView(ScrollView(this).apply { addView(rows) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        content.addView(TextView(this).apply {
            setText(R.string.private_image_no_submission)
        })
        setContentView(content)
        if (projectId.isBlank() || !isUuid(workId) || !activeProjectMatches()) {
            deny()
            return
        }
        refresh()
    }

    private fun activeProjectMatches() =
        DaggerUtils.getComponent(this)
            .currentProjectProvider().getCurrentProject().value?.uuid == projectId

    private fun actorHash(): String {
        val value = settings.getUnprotectedSettings(projectId)
        val paths = RecordWorkApiPaths.fromProjectUrl(
            value.getString(ProjectKeys.KEY_SERVER_URL).orEmpty()
        )
        val username = value.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        require(username.isNotBlank())
        return QuarantinedWorkDraftVault.sha256(
            projectId + "\n" + paths.records() + "\n" + username
        )
    }

    private fun vault() = QuarantinedWorkDraftVault(noBackupFilesDir)
    private fun client() = RecordWorkPreparationClient(settings)
    private fun store() = QuarantinedPrivateImageStore(
        noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
    )

    private data class AuthorizedImages(
        val binding: QuarantinedWorkDraft,
        val paths: List<String>
    )

    private fun authorized(): AuthorizedImages {
        require(activeProjectMatches()) { "Active project changed" }
        val binding = requireNotNull(
            vault().read(projectId, workId, actorHash())
        ) { "Private work binding missing" }
        val form = client().authorizePrivateTextForm(projectId, binding)
        val probe = JavaRosaPrivateImageFieldProbe(
            cacheDir, binding, form.plan, form.targetXFormXml
        )
        return AuthorizedImages(binding, probe.imagePaths)
    }

    private data class Display(
        val authorization: AuthorizedImages,
        val receipts: Map<String, ProtectedImageReceipt?>
    )

    private fun refresh() {
        rows.removeAllViews()
        progress.visibility = View.VISIBLE
        status.setText(R.string.private_image_loading)
        val token = ++generation
        executor.execute {
            val result = runCatching {
                val verified = authorized()
                val receipts = verified.paths.associateWith { field ->
                    store().receipt(verified.binding, field, verified.paths)
                }
                Display(verified, receipts)
            }
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess { render(it) }.onFailure { deny() }
            }
        }
    }

    private fun render(display: Display) {
        active = display.authorization.binding
        paths = display.authorization.paths
        rows.removeAllViews()
        status.setText(R.string.private_image_ready)
        display.authorization.paths.forEach { path ->
            rows.addView(TextView(this).apply {
                text = path
                setPadding(0, 18, 0, 6)
            })
            val previous = display.receipts[path]
            rows.addView(TextView(this).apply {
                text = if (previous != null)
                    getString(R.string.private_image_staged, previous.bytes)
                else getString(R.string.private_image_missing)
            })
            rows.addView(Button(this).apply {
                setText(if (previous == null) R.string.private_image_choose
                    else R.string.private_image_replace)
                isAllCaps = false
                setOnClickListener {
                    chosenPath = path
                    picker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                }
            })
            if (previous != null) {
                rows.addView(Button(this).apply {
                    setText(R.string.private_image_clear)
                    isAllCaps = false
                    setOnClickListener { clear(path) }
                })
            }
        }
    }

    private fun importSelected(field: String, uri: Uri) {
        progress.visibility = View.VISIBLE
        rows.removeAllViews()
        status.setText(R.string.private_image_checking)
        val expected = active
        val token = ++generation
        executor.execute {
            val result = runCatching {
                val authorized = authorized()
                require(authorized.binding == expected && field in authorized.paths) {
                    "Private media form or work changed"
                }
                val mime = requireNotNull(contentResolver.getType(uri)) {
                    "Unrecognized media MIME type"
                }
                val input = requireNotNull(contentResolver.openInputStream(uri)) {
                    "Content URI could not be opened"
                }
                store().importSelectedImage(
                    authorized.binding, field, authorized.paths, mime, input
                )
                val verified = requireNotNull(
                    store().receipt(authorized.binding, field, authorized.paths)
                ) { "Encrypted image was not recoverable" }
                require(verified.bytes > 0) { "Invalid saved image" }
            }
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess { refresh() }.onFailure { deny() }
            }
        }
    }

    private fun clear(field: String) {
        progress.visibility = View.VISIBLE
        rows.removeAllViews()
        status.setText(R.string.private_image_checking)
        val expected = active
        val token = ++generation
        executor.execute {
            val result = runCatching {
                val authorized = authorized()
                require(authorized.binding == expected && field in authorized.paths)
                store().clear(authorized.binding, field, authorized.paths)
            }
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess { refresh() }.onFailure { deny() }
            }
        }
    }

    private fun deny() {
        active = null
        paths = emptyList()
        chosenPath = null
        rows.removeAllViews()
        progress.visibility = View.GONE
        status.setText(R.string.private_image_denied)
    }

    override fun onDestroy() {
        generation++
        executor.shutdownNow()
        active = null
        chosenPath = null
        paths = emptyList()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROJECT = "recordwork.image.project"
        const val EXTRA_WORK = "recordwork.image.work"

        fun intentFor(
            context: Context,
            projectId: String,
            workId: String
        ): Intent = Intent(context, QuarantinedImageIntakeActivity::class.java)
            .putExtra(EXTRA_PROJECT, projectId)
            .putExtra(EXTRA_WORK, workId)

        private fun isUuid(value: String) =
            runCatching { UUID.fromString(value).toString() == value }
                .getOrDefault(false)
    }
}
