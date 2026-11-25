package org.odk.collect.android.recordwork

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.odk.collect.android.R
import org.odk.collect.android.injection.DaggerUtils
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.strings.localization.LocalizedActivity
import java.util.concurrent.Executors

/**
 * Protected working-copy editor and explicit checkpoint publication.
 * Checkpoints are read-only evidence; no ordinary ODK Instance is created.
 * Returning from component editors reloads all state with fresh READY authorization.
 */
class QuarantinedCombinedDraftActivity : LocalizedActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var settings: SettingsProvider
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private val fields = linkedMapOf<String, EditText>()
    private var token = 0
    private var display: Display? = null
    private val project get() = intent.getStringExtra(EXTRA_PROJECT).orEmpty()
    private val work get() = intent.getStringExtra(EXTRA_WORK).orEmpty()

    private data class Display(
        val binding: QuarantinedWorkDraft,
        val values: Map<String, String>,
        val activeRows: Int,
        val committed: ProtectedCommittedDraft?,
        val finalization: ProtectedCommittedDraftStore.FinalizationState?,
        val eligibilityChecked: Boolean? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        title = getString(R.string.private_combined_open)
        settings = DaggerUtils.getComponent(this).settingsProvider()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        status = TextView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(status)
        layout.addView(TextView(this).apply { setText(R.string.private_combined_warning) })
        layout.addView(ScrollView(this).apply { addView(content) })
        setContentView(layout)
    }

    override fun onStart() {
        super.onStart()
        perform { load() }
    }

    private fun activeProject() = DaggerUtils.getComponent(this)
        .currentProjectProvider().getCurrentProject().value?.uuid == project

    private fun binding(): QuarantinedWorkDraft {
        require(project.isNotBlank() && activeProject())
        val values = settings.getUnprotectedSettings(project)
        val username = values.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        require(username.isNotBlank())
        val scope = QuarantinedWorkDraftVault.sha256(project + "\n" +
            RecordWorkApiPaths.fromProjectUrl(values.getString(ProjectKeys.KEY_SERVER_URL).orEmpty())
                .records() + "\n" + username)
        return requireNotNull(QuarantinedWorkDraftVault(noBackupFilesDir).read(project, work, scope))
    }

    private fun checkpoints() = ProtectedCommittedDraftStore(cacheDir, noBackupFilesDir, settings)
    private fun probe() = ProtectedNativeRepeatImageProbe(cacheDir, noBackupFilesDir, settings)

    private fun load(): Display {
        val b = binding()
        require(b.workType == RecordWorkType.FOLLOW_UP && b.validationTransfers == 0)
        val form = RecordWorkPreparationClient(settings).authorizePrivateTextForm(project, b)
        val cipher = AndroidKeyStorePrivateDraftCipher()
        val approval = requireNotNull(ProtectedMappingApprovalStore(noBackupFilesDir, cipher)
            .read(b, form.plan))
        require(approval.decisions.values.all { it == MappingDecision.REENTER })
        val session = JavaRosaPrivateRepeatAnswerSession(cacheDir, b, form.plan, form.targetXFormXml, true)
        val group = session.schema.groups.single()
        require(group.imagePaths.isNotEmpty())
        val values = EncryptedPrivateTextDraftStore(noBackupFilesDir, cipher)
            .read(b, session.schema.directTextPaths).answers
        val count = QuarantinedRepeatRowVault(noBackupFilesDir, cipher)
            .read(b, session.schema).active(group.path).size
        val committed = checkpoints().reopen(project, b)
        return Display(b, session.schema.directTextPaths.associateWith { values[it].orEmpty() },
            count, committed, committed?.let {
                checkpoints().finalizationState(project, b, it.generation)
            })
    }

    private fun clear() {
        fields.values.forEach { it.text?.clear() }
        fields.clear()
        content.removeAllViews()
        display = null
    }

    private fun perform(action: () -> Display) {
        clear()
        status.setText(R.string.private_combined_checking)
        val request = ++token
        worker.execute {
            val outcome = runCatching(action)
            runOnUiThread {
                if (request != token || isFinishing || isDestroyed) return@runOnUiThread
                if (!activeProject() || outcome.getOrNull()?.let {
                        runCatching { binding() == it.binding }.getOrDefault(false)
                    } == false) {
                    clear()
                    status.setText(R.string.private_combined_denied)
                    return@runOnUiThread
                }
                outcome.onSuccess { render(it) }.onFailure {
                    clear()
                    status.setText(R.string.private_combined_denied)
                    button(R.string.private_combined_reload) { perform { load() } }
                }
            }
        }
    }

    private fun values() = fields.mapValues { it.value.text.toString() }

    private fun button(label: Int, click: () -> Unit): Button =
        Button(this).apply {
            isAllCaps = false
            setText(label)
            setOnClickListener { click() }
            content.addView(this)
        }

    private fun requireSaved(state: Display): Boolean {
        if (values() == state.values) return true
        status.setText(R.string.private_combined_unsaved)
        return false
    }

    private fun render(state: Display) {
        display = state
        status.text = if (state.committed == null) getString(R.string.private_combined_no_checkpoint)
            else getString(R.string.private_combined_checkpoint, state.committed.generation)
        for ((path, value) in state.values) {
            content.addView(TextView(this).apply { text = path })
            fields[path] = EditText(this).apply {
                isSaveEnabled = false
                filters = arrayOf(InputFilter.LengthFilter(JavaRosaPrivateRepeatAnswerSession.MAX_TEXT))
                setText(value)
                content.addView(this)
            }
        }
        button(R.string.private_combined_save_text) {
            val entered = values()
            perform {
                require(binding() == state.binding)
                probe().saveDirectText(project, state.binding, entered, state.values)
                load()
            }
        }
        button(R.string.mapping_open_private_image_intake) {
            if (requireSaved(state)) startActivity(
                QuarantinedImageIntakeActivity.intentFor(this, project, work))
        }
        button(R.string.mapping_open_private_repeats) {
            if (requireSaved(state)) startActivity(
                QuarantinedRepeatRowsActivity.intentFor(this, project, work, true))
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.private_combined_rows, state.activeRows)
        })
        button(R.string.private_combined_publish) {
            if (!requireSaved(state)) return@button
            AlertDialog.Builder(this)
                .setTitle(R.string.private_combined_publish)
                .setMessage(R.string.private_combined_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    perform {
                        require(binding() == state.binding)
                        val committed = checkpoints().commit(project, state.binding,
                            state.committed?.generation)
                        val reopened = load()
                        require(reopened.committed == committed)
                        reopened
                    }
                }.show()
        }.isEnabled = state.activeRows > 0
        val finalizationStatus = TextView(this).apply {
            setPadding(0, 16, 0, 6)
            setText(when (state.finalization) {
                null -> R.string.private_finalization_checkpoint_required
                ProtectedCommittedDraftStore.FinalizationState.DIRTY -> R.string.private_finalization_dirty
                ProtectedCommittedDraftStore.FinalizationState.NOT_FINALIZED -> when (state.eligibilityChecked) {
                    true -> R.string.private_finalization_eligible
                    false -> R.string.private_finalization_ineligible
                    null -> R.string.private_finalization_not_yet
                }
                ProtectedCommittedDraftStore.FinalizationState.FINALIZED -> R.string.private_finalization_complete
            })
        }
        content.addView(finalizationStatus)
        button(R.string.private_finalization_action) {
            if (!requireSaved(state)) return@button
            val generation = state.committed?.generation ?: return@button
            AlertDialog.Builder(this)
                .setTitle(R.string.private_finalization_action)
                .setMessage(R.string.private_finalization_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    perform {
                        require(binding() == state.binding)
                        val result = runCatching {
                            checkpoints().persistFinalizedPackage(project, state.binding, generation)
                        }
                        val reopened = load()
                        require(reopened.committed?.generation == generation)
                        if (result.isSuccess) {
                            require(reopened.finalization == ProtectedCommittedDraftStore.FinalizationState.FINALIZED)
                            reopened
                        } else reopened.copy(eligibilityChecked = false)
                    }
                }.show()
        }.isEnabled = state.finalization == ProtectedCommittedDraftStore.FinalizationState.NOT_FINALIZED
        button(R.string.private_finalization_check) {
            if (!requireSaved(state)) return@button
            val generation = state.committed?.generation ?: return@button
            // Check without writing any package; reload with fresh auth even
            // on failure rather than displaying a possibly stale success.
            perform {
                require(binding() == state.binding)
                val result = runCatching {
                    checkpoints().verifyNativeAttachmentPackage(project, state.binding, generation)
                }
                val refreshed = load()
                require(refreshed.committed?.generation == generation)
                refreshed.copy(eligibilityChecked = result.isSuccess)
            }
        }.isEnabled = state.finalization == ProtectedCommittedDraftStore.FinalizationState.NOT_FINALIZED
        button(R.string.private_combined_reload) {
            if (requireSaved(state)) perform { load() }
        }
    }

    override fun onStop() {
        token++
        clear()
        super.onStop()
    }

    override fun onDestroy() {
        token++
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PROJECT = "recordwork.combined.project"
        private const val EXTRA_WORK = "recordwork.combined.work"
        internal const val EXTRA_MIXED = "recordwork.combined.mixed"

        fun intentFor(context: Context, project: String, work: String) =
            Intent(context, QuarantinedCombinedDraftActivity::class.java)
                .putExtra(EXTRA_PROJECT, project).putExtra(EXTRA_WORK, work)
    }
}
