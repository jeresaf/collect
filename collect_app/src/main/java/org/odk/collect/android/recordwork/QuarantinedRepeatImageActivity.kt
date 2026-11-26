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
 * Private repeat-ROW image picker. The only locator in the intent is the
 * permanent UUID, not an ordinal, original image URI, or answer content.
 *
 * READY server + exact JavaRosa schema + active row is reauthorized before
 * every open, import/read and clear. The image stays encrypted and never
 * becomes an ODK binary answer, form instance or upload attachment.
 */
class QuarantinedRepeatImageActivity : LocalizedActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var settings: SettingsProvider
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var rows: LinearLayout
    private var generation = 0
    private var openedBinding: QuarantinedWorkDraft? = null
    private var chosenField: String? = null

    private val projectId get() = intent.getStringExtra(EXTRA_PROJECT).orEmpty()
    private val workId get() = intent.getStringExtra(EXTRA_WORK).orEmpty()
    private val rowId get() = intent.getStringExtra(EXTRA_ROW).orEmpty()

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val selected = chosenField
        chosenField = null
        if (uri != null && selected != null && !isFinishing && !isDestroyed) {
            importSelected(selected, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        settings = DaggerUtils.getComponent(this).settingsProvider()
        title = getString(R.string.private_repeat_image_title)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        status = TextView(this).apply {
            setText(R.string.private_repeat_image_checking)
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(status)
        root.addView(progress)
        root.addView(ScrollView(this).apply { addView(rows) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        root.addView(TextView(this).apply {
            setText(R.string.private_repeat_image_no_submit)
        })
        setContentView(root)
        if (projectId.isBlank() || !isUuid(workId) || !isUuid(rowId) ||
            !activeProjectMatches()) {
            deny()
            return
        }
        refresh()
    }

    private fun activeProjectMatches(): Boolean =
        DaggerUtils.getComponent(this)
            .currentProjectProvider().getCurrentProject().value?.uuid == projectId

    private fun actorHash(): String {
        val values = settings.getUnprotectedSettings(projectId)
        val url = RecordWorkApiPaths.fromProjectUrl(
            values.getString(ProjectKeys.KEY_SERVER_URL).orEmpty()
        )
        val username = values.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        require(username.isNotBlank())
        return QuarantinedWorkDraftVault.sha256(
            projectId + "\n" + url.records() + "\n" + username
        )
    }

    private data class Authorized(
        val binding: QuarantinedWorkDraft,
        val session: JavaRosaPrivateRepeatAnswerSession,
        val paths: List<String>
    )

    private fun authorized(): Authorized {
        require(activeProjectMatches()) { "Active project changed" }
        val binding = requireNotNull(
            QuarantinedWorkDraftVault(noBackupFilesDir)
                .read(projectId, workId, actorHash())
        ) { "Private work item binding missing" }
        ProtectedSubmissionAttemptGuard.requireEditable(noBackupFilesDir, binding)
        val prepared = RecordWorkPreparationClient(settings)
            .authorizePrivateTextForm(projectId, binding)
        val mixed = intent.getBooleanExtra(QuarantinedCombinedDraftActivity.EXTRA_MIXED, false)
        if (mixed) {
            require(ProtectedReentryWork.eligible(binding))
            val approval = requireNotNull(ProtectedMappingApprovalStore(
                noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()).read(binding, prepared.plan))
            require(approval.decisions.values.all { it == MappingDecision.REENTER })
        }
        val session = JavaRosaPrivateRepeatAnswerSession(
            cacheDir, binding, prepared.plan, prepared.targetXFormXml, mixed
        )
        val row = QuarantinedRepeatRowVault(
            noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
        ).read(binding, session.schema).rows.singleOrNull {
            it.rowId == rowId && !it.isArchived
        } ?: throw IllegalArgumentException("Repeat row archived or unknown")
        val group = requireNotNull(
            session.schema.groups.singleOrNull { it.path == row.groupPath }
        ) { "Native repeat group changed" }
        require(group.imagePaths.isNotEmpty()) {
            "No approved direct image fields for this repeat"
        }
        return Authorized(binding, session, group.imagePaths)
    }

    private fun store() = QuarantinedRepeatImageStore(
        noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
    )

    private data class Display(
        val allowed: Authorized,
        val receipts: Map<String, ProtectedRepeatImageReceipt?>
    )

    private fun refresh() {
        progress.visibility = View.VISIBLE
        status.setText(R.string.private_repeat_image_checking)
        rows.removeAllViews()
        val token = ++generation
        executor.execute {
            val result = runCatching {
                val auth = authorized()
                Display(auth, auth.paths.associateWith { path ->
                    store().receipt(auth.binding, auth.session, rowId, path)
                })
            }
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed)
                    return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess { render(it) }.onFailure { deny() }
            }
        }
    }

    private fun render(display: Display) {
        openedBinding = display.allowed.binding
        status.text = getString(R.string.private_repeat_image_ready, rowId)
        rows.removeAllViews()
        display.allowed.paths.forEach { path ->
            rows.addView(TextView(this).apply {
                text = path
                setPadding(0, 20, 0, 6)
            })
            val old = display.receipts[path]
            rows.addView(TextView(this).apply {
                text = if (old == null)
                    getString(R.string.private_repeat_image_missing)
                else getString(
                    R.string.private_repeat_image_staged,
                    old.bytes, old.sha256.take(12)
                )
            })
            rows.addView(Button(this).apply {
                setText(if (old == null)
                    R.string.private_repeat_image_choose
                else R.string.private_repeat_image_replace)
                isAllCaps = false
                setOnClickListener {
                    chosenField = path
                    picker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                }
            })
            if (old != null) {
                rows.addView(Button(this).apply {
                    setText(R.string.private_repeat_image_clear)
                    isAllCaps = false
                    setOnClickListener { clear(path) }
                })
            }
        }
    }

    private fun importSelected(path: String, uri: Uri) {
        val initial = openedBinding ?: return deny()
        progress.visibility = View.VISIBLE
        rows.removeAllViews()
        status.setText(R.string.private_repeat_image_saving)
        val token = ++generation
        executor.execute {
            val outcome = runCatching {
                val auth = authorized()
                require(auth.binding == initial && path in auth.paths) {
                    "Native repeat image field or work was changed"
                }
                val mime = requireNotNull(contentResolver.getType(uri))
                val stream = requireNotNull(contentResolver.openInputStream(uri))
                val staged = store().importSelectedImage(
                    auth.binding, auth.session, rowId, path, mime, stream
                )
                val confirmed = requireNotNull(
                    store().receipt(auth.binding, auth.session, rowId, path)
                )
                require(staged == confirmed) { "Repeat image encrypted readback differs" }
            }
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed)
                    return@runOnUiThread
                progress.visibility = View.GONE
                outcome.onSuccess { refresh() }.onFailure { deny() }
            }
        }
    }

    private fun clear(path: String) {
        val initial = openedBinding ?: return deny()
        progress.visibility = View.VISIBLE
        rows.removeAllViews()
        status.setText(R.string.private_repeat_image_saving)
        val token = ++generation
        executor.execute {
            val outcome = runCatching {
                val auth = authorized()
                require(auth.binding == initial && path in auth.paths) {
                    "Repeat image scope changed before deletion"
                }
                store().clear(auth.binding, auth.session, rowId, path)
                require(store().receipt(
                    auth.binding, auth.session, rowId, path
                ) == null) { "Protected image was not cleared" }
            }
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed)
                    return@runOnUiThread
                progress.visibility = View.GONE
                outcome.onSuccess { refresh() }.onFailure { deny() }
            }
        }
    }

    private fun deny() {
        openedBinding = null
        chosenField = null
        rows.removeAllViews()
        progress.visibility = View.GONE
        status.setText(R.string.private_repeat_image_denied)
    }

    override fun onDestroy() {
        generation++
        executor.shutdownNow()
        openedBinding = null
        chosenField = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PROJECT = "recordwork.repeatimage.project"
        private const val EXTRA_WORK = "recordwork.repeatimage.work"
        private const val EXTRA_ROW = "recordwork.repeatimage.uuid"

        fun intentFor(
            context: Context,
            projectId: String,
            workId: String,
            rowId: String,
            mixed: Boolean = false
        ): Intent = Intent(context, QuarantinedRepeatImageActivity::class.java)
            .putExtra(EXTRA_PROJECT, projectId)
            .putExtra(EXTRA_WORK, workId)
            .putExtra(EXTRA_ROW, rowId)
            .putExtra(QuarantinedCombinedDraftActivity.EXTRA_MIXED, mixed)

        private fun isUuid(value: String) =
            runCatching { UUID.fromString(value).toString() == value }
                .getOrDefault(false)
    }
}
