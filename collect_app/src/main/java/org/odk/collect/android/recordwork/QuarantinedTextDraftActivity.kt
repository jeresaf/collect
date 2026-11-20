package org.odk.collect.android.recordwork

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
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
    private val inputs = linkedMapOf<String, EditText>()
    private lateinit var status: TextView
    private lateinit var formRows: LinearLayout
    private lateinit var saveButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var settings: SettingsProvider
    private var activeBinding: QuarantinedWorkDraft? = null
    private var formPaths = emptyList<String>()
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
        val fields: List<String>,
        val answers: Map<String, String>,
        val previouslySaved: Boolean
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
                val native = JavaRosaPrivateTextSession(
                    cacheDir, binding, authorized.plan, authorized.targetXFormXml
                )
                val stored = EncryptedPrivateTextDraftStore(
                    noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
                ).read(binding, native.fields)
                OpenResult(binding, native.fields,
                    native.validateForPrivateSave(stored.answers), stored.hasSavedDraft)
            }
            runOnUiThread {
                if (request != generation || isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess { render(it) }.onFailure { reject() }
            }
        }
    }

    private fun render(result: OpenResult) {
        activeBinding = result.binding
        formPaths = result.fields
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
        result.fields.forEach { path ->
            formRows.addView(TextView(this).apply {
                text = path
                contentDescription = path
                setPadding(0, 22, 0, 5)
            })
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                filters = arrayOf(InputFilter.LengthFilter(JavaRosaPrivateTextSession.MAX_TEXT))
                setText(result.answers[path].orEmpty())
                setSingleLine(false)
            }
            formRows.addView(input)
            inputs[path] = input
        }
        saveButton.isEnabled = true
    }

    private fun save() {
        val original = activeBinding ?: return reject()
        if (!activeProjectMatches() || inputs.size != formPaths.size) return reject()
        val candidate = inputs.mapValues { (_, input) -> input.text.toString() }
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
                val native = JavaRosaPrivateTextSession(
                    cacheDir, binding, form.plan, form.targetXFormXml
                )
                require(native.fields == formPaths) { "Question inventory changed" }
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
                }.onFailure {
                    // Never print server/body/PII/exception to the UI.
                    status.setText(R.string.private_text_editor_save_failed)
                    saveButton.isEnabled = false
                }
            }
        }
    }

    private fun reject() {
        activeBinding = null
        formPaths = emptyList()
        saveButton.isEnabled = false
        formRows.removeAllViews()
        inputs.clear()
        progress.visibility = View.GONE
        status.setText(R.string.private_text_editor_denied)
    }

    override fun onDestroy() {
        generation++
        executor.shutdownNow()
        activeBinding = null
        formPaths = emptyList()
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
