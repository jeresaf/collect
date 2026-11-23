package org.odk.collect.android.recordwork

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.odk.collect.android.R
import org.odk.collect.android.injection.DaggerUtils
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.strings.localization.LocalizedActivity
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Target-version text-only record editor isolated from the ordinary ODK
 * FormUriActivity, InstancesRepository and OpenRosa sender. An imported old
 * instance must NEVER be interpreted under an updated XForm.
 *
 * Each open AND each save separately confirms server READY state and source
 * + target hashes. Answers are persisted only as Android Keystore AES-GCM
 * ciphertext inside the no-backup quarantined vault. There is no Finish/Send.
 */
class QuarantinedTextDraftActivity : LocalizedActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val inputs = linkedMapOf<String, () -> String>()
    private lateinit var status: TextView
    private lateinit var formRows: LinearLayout
    private lateinit var saveButton: Button
    private lateinit var approvedImportButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var settings: SettingsProvider
    private var activeBinding: QuarantinedWorkDraft? = null
    private var formPaths = emptyList<String>()
    private var formFields = emptyList<PrivateScalarField>()
    private var generation = 0

    private val projectId get() = intent.getStringExtra(EXTRA_PROJECT).orEmpty()
    private val workId get() = intent.getStringExtra(EXTRA_WORK).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        title = getString(R.string.private_text_editor_title)
        settings = DaggerUtils.getComponent(this).settingsProvider()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        status = TextView(this).apply {
            setText(R.string.private_text_editor_loading)
            textSize = 16f
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        saveButton = Button(this).apply {
            setText(R.string.private_text_editor_save)
            isEnabled = false
            setOnClickListener { save() }
        }
        content.addView(status)
        content.addView(progress)
        formRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(formRows)
        }
        content.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        approvedImportButton = Button(this).apply {
            setText(R.string.mapping_approved_import_action)
            isAllCaps = false
            visibility = View.GONE
            isEnabled = false
            setOnClickListener {
                AlertDialog.Builder(this@QuarantinedTextDraftActivity)
                    .setTitle(R.string.mapping_approved_import_title)
                    .setMessage(R.string.mapping_approved_import_warning)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        applyApprovedHistoricalText()
                    }
                    .show()
            }
        }
        content.addView(approvedImportButton)
        content.addView(saveButton)
        content.addView(TextView(this).apply {
            setText(R.string.private_text_editor_never_submit)
        })
        setContentView(content)

        if (!isUuid(workId) || projectId.isBlank() || !activeProjectMatches()) {
            reject()
            return
        }
        open()
    }

    private fun activeProjectMatches(): Boolean {
        val current = DaggerUtils.getComponent(this)
            .currentProjectProvider().getCurrentProject().value
        return current != null && current.uuid == projectId
    }

    private fun actorHash(): String {
        val values = settings.getUnprotectedSettings(projectId)
        val paths = RecordWorkApiPaths.fromProjectUrl(
            values.getString(ProjectKeys.KEY_SERVER_URL).orEmpty()
        )
        val username = values.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        require(username.isNotBlank()) { "Active authenticated account required" }
        return QuarantinedWorkDraftVault.sha256(
            projectId + "\n" + paths.records() + "\n" + username
        )
    }

    private fun vault() = QuarantinedWorkDraftVault(noBackupFilesDir)

    private fun client() = RecordWorkPreparationClient(settings)

    private data class OpenResult(
        val binding: QuarantinedWorkDraft,
        val fields: List<PrivateScalarField>,
        val answers: Map<String, String>,
        val previouslySaved: Boolean,
        val canImportApprovedOriginal: Boolean
    )

    private fun open() {
        saveButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.setText(R.string.private_text_editor_loading)
        val request = ++generation
        executor.execute {
            val result = runCatching {
                require(activeProjectMatches()) { "Active project changed" }
                val binding = requireNotNull(
                    vault().read(projectId, workId, actorHash())
                ) { "Protected work draft is not staged" }
                val authorized = client().authorizePrivateTextForm(projectId, binding)
                val native = JavaRosaPrivateScalarSession(
                    cacheDir, binding, authorized.plan, authorized.targetXFormXml
                )
                val stored = EncryptedPrivateTextDraftStore(
                    noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
                ).read(binding, native.fields)
                val approved = if (
                    binding.workType == RecordWorkType.AMENDMENT &&
                    binding.validationTransfers > 0 && !stored.hasSavedDraft
                ) {
                    ProtectedMappingApprovalStore(
                        noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
                    ).read(binding, authorized.plan)
                } else null
                OpenResult(
                    binding, native.questions,
                    native.validateForPrivateSave(stored.answers), stored.hasSavedDraft,
                    approved?.approvedTransferPaths?.size == binding.validationTransfers &&
                        binding.validationTransfers > 0
                )
            }
            runOnUiThread {
                if (request != generation || isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess { render(it) }.onFailure { reject(it) }
            }
        }
    }

    private fun render(result: OpenResult) {
        activeBinding = result.binding
        formFields = result.fields
        formPaths = result.fields.map { it.path }
        inputs.clear()
        formRows.removeAllViews()
        status.text = getString(
            R.string.private_text_editor_ready, result.binding.targetVersion,
            if (result.previouslySaved) getString(R.string.private_text_editor_recovered)
            else getString(R.string.private_text_editor_new)
        )
        formRows.addView(TextView(this).apply {
            setText(R.string.private_text_editor_manual)
        })
        result.fields.forEach { field ->
            formRows.addView(TextView(this).apply {
                text = field.path + " (" + field.type.name.lowercase() + ")"
                contentDescription = field.path + ", " + field.type.name.lowercase()
                setPadding(0, 22, 0, 5)
            })
            when (field.type) {
                PrivateScalarType.SELECT_ONE -> {
                    val choiceLabels = listOf(getString(R.string.private_choice_none)) +
                        field.choices.map { choice ->
                            if (choice.label == choice.value) choice.value
                            else choice.label + " (" + choice.value + ")"
                        }
                    val picker = Spinner(this).apply {
                        adapter = ArrayAdapter(
                            this@QuarantinedTextDraftActivity,
                            android.R.layout.simple_spinner_dropdown_item, choiceLabels
                        )
                        contentDescription = field.path
                        val selected = field.choices.indexOfFirst { it.value ==
                            result.answers[field.path].orEmpty() }
                        setSelection(if (selected < 0) 0 else selected + 1)
                    }
                    formRows.addView(picker)
                    inputs[field.path] = {
                        val selected = picker.selectedItemPosition
                        if (selected <= 0) "" else field.choices[selected - 1].value
                    }
                }
                PrivateScalarType.SELECT_MULTI -> {
                    val saved = result.answers[field.path].orEmpty()
                        .split(" ").filter(String::isNotEmpty).toSet()
                    val boxes = field.choices.map { choice ->
                        CheckBox(this).apply {
                            text = if (choice.label == choice.value) choice.value
                                else choice.label + " (" + choice.value + ")"
                            isChecked = choice.value in saved
                            contentDescription = field.path + ": " + choice.value
                            formRows.addView(this)
                        } to choice.value
                    }
                    inputs[field.path] = {
                        boxes.filter { (box, _) -> box.isChecked }
                            .joinToString(" ") { (_, value) -> value }
                    }
                }
                else -> {
                    val input = EditText(this).apply {
                        inputType = when (field.type) {
                            PrivateScalarType.TEXT ->
                                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            PrivateScalarType.INTEGER ->
                                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                            PrivateScalarType.DECIMAL ->
                                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                                    InputType.TYPE_NUMBER_FLAG_DECIMAL
                            PrivateScalarType.DATE -> InputType.TYPE_CLASS_TEXT
                            else -> error("Unexpected private answer type")
                        }
                        hint = when (field.type) {
                            PrivateScalarType.DATE -> "YYYY-MM-DD"
                            PrivateScalarType.INTEGER -> "Integer"
                            PrivateScalarType.DECIMAL -> "Decimal (period)"
                            else -> ""
                        }
                        val maximum = if (field.type == PrivateScalarType.TEXT)
                            JavaRosaPrivateScalarSession.MAX_TEXT else 40
                        filters = arrayOf(InputFilter.LengthFilter(maximum))
                        setText(result.answers[field.path].orEmpty())
                        setSingleLine(field.type != PrivateScalarType.TEXT)
                    }
                    formRows.addView(input)
                    inputs[field.path] = { input.text.toString() }
                }
            }
        }
        approvedImportButton.visibility =
            if (result.canImportApprovedOriginal) View.VISIBLE else View.GONE
        approvedImportButton.isEnabled = result.canImportApprovedOriginal
        saveButton.isEnabled = true
    }

    /**
     * Opt-in only. Never prefill on open, never copy changed types/repeats,
     * and never overwrite a manually saved encrypted draft. Source XML
     * exists in RAM during this operation only, not in Intent or a file.
     */
    private fun applyApprovedHistoricalText() {
        val originalBinding = activeBinding ?: return reject()
        if (!activeProjectMatches() || formPaths.isEmpty()) return reject()
        // A draft can be unsaved but already contain an enumerator's work.
        // Never silently discard those answers by applying old values.
        if (inputs.values.any { read -> read().isNotEmpty() }) {
            status.setText(R.string.mapping_approved_import_unsaved)
            return
        }
        approvedImportButton.isEnabled = false
        saveButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.setText(R.string.mapping_approved_import_checking)
        val request = ++generation
        executor.execute {
            val result = runCatching {
                require(activeProjectMatches()) { "Active project changed" }
                val binding = requireNotNull(
                    vault().read(projectId, workId, actorHash())
                ) { "Protected work binding missing" }
                require(binding == originalBinding &&
                    binding.workType == RecordWorkType.AMENDMENT &&
                    binding.validationTransfers > 0) {
                    "Historical transfer scope changed"
                }
                val source = client().authorizeReviewedSource(projectId, binding)
                val approval = requireNotNull(
                    ProtectedMappingApprovalStore(
                        noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
                    ).read(binding, source.plan)
                ) { "Durable per-field operator approval is missing" }
                require(approval.approvedTransferPaths.isNotEmpty())
                val native = JavaRosaPrivateScalarSession(
                    cacheDir, binding, source.plan, source.targetXFormXml
                )
                require(native.fields == formPaths &&
                    native.questions == formFields) {
                    "Target native scalar inventory or choices changed"
                }
                val candidate = ApprovedHistoricalTextImporter(cacheDir)
                    .validateAndPropose(binding, approval, source, native)
                val store = EncryptedPrivateTextDraftStore(
                    noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
                )
                store.saveIfAbsent(binding, candidate, native.fields)
                val recovered = store.read(binding, native.fields)
                require(recovered.hasSavedDraft && recovered.answers == candidate) {
                    "Approved transfer encrypted readback differs"
                }
                true
            }
            runOnUiThread {
                if (request != generation || isFinishing || isDestroyed)
                    return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess { open() }.onFailure { error ->
                    // Clear potentially sensitive on-screen answers whenever
                    // the server no longer authorizes this exact work. Only
                    // already encrypted/saved values remain in private storage.
                    reject(error)
                }
            }
        }
    }

    private fun save() {
        val original = activeBinding ?: return reject()
        if (!activeProjectMatches() || inputs.size != formPaths.size) return reject()
        val candidate = inputs.mapValues { (_, read) -> read() }
        saveButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.setText(R.string.private_text_editor_saving)
        val request = ++generation
        executor.execute {
            val result = runCatching {
                // Re-read local binding and reauthorize online. No "offline"
                // bypass is permitted if work was revoked or project changed.
                require(activeProjectMatches()) { "Active project changed" }
                val binding = requireNotNull(
                    vault().read(projectId, workId, actorHash())
                ) { "Protected work draft missing" }
                require(binding == original) { "Work identity changed during edit" }
                val form = client().authorizePrivateTextForm(projectId, binding)
                val native = JavaRosaPrivateScalarSession(
                    cacheDir, binding, form.plan, form.targetXFormXml
                )
                require(native.fields == formPaths && native.questions == formFields) {
                    "Question type or inventory changed"
                }
                val validated = native.validateForPrivateSave(candidate)
                EncryptedPrivateTextDraftStore(
                    noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
                ).save(binding, validated, native.fields)
                // Verify encrypted readback under the same exact binding.
                val persisted = EncryptedPrivateTextDraftStore(
                    noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
                ).read(binding, native.fields)
                require(persisted.hasSavedDraft && persisted.answers == validated) {
                    "Private encrypted save verification failed"
                }
                true
            }
            runOnUiThread {
                if (request != generation || isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess {
                    status.setText(R.string.private_text_editor_saved)
                    saveButton.isEnabled = true
                }.onFailure { error ->
                    // Even if the server revoked access while the form was
                    // open, no fields or unsaved answers may stay on screen.
                    // reject() does not delete the encrypted private vault.
                    reject(error)
                }
            }
        }
    }

    private fun reject(error: Throwable? = null) {
        activeBinding = null
        formPaths = emptyList()
        formFields = emptyList()
        saveButton.isEnabled = false
        approvedImportButton.isEnabled = false
        approvedImportButton.visibility = View.GONE
        formRows.removeAllViews()
        inputs.clear()
        progress.visibility = View.GONE
        if (error == null) status.setText(R.string.private_text_editor_denied)
        else status.text = ProtectedWorkRecovery.forFailure(error).message
    }

    override fun onStop() {
        // The editor must not leave plaintext answers in views while its
        // account/project may be switched or its server authority revoked.
        // No disk writes or ciphertext deletion occur in reject().
        generation++
        reject()
        super.onStop()
    }

    override fun onRestart() {
        super.onRestart()
        // Every foreground return requires a new authenticated READY check;
        // encrypted drafts are reopened only after that check succeeds.
        if (isUuid(workId) && projectId.isNotBlank() && activeProjectMatches()) {
            open()
        } else reject()
    }

    override fun onDestroy() {
        generation++
        executor.shutdownNow()
        activeBinding = null
        formPaths = emptyList()
        formFields = emptyList()
        inputs.clear()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROJECT = "recordwork.private.project"
        const val EXTRA_WORK = "recordwork.private.work"

        fun intentFor(
            from: android.content.Context,
            projectId: String,
            workId: String
        ): Intent = Intent(from, QuarantinedTextDraftActivity::class.java)
            .putExtra(EXTRA_PROJECT, projectId)
            .putExtra(EXTRA_WORK, workId)

        private fun isUuid(value: String): Boolean =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
