package org.odk.collect.android.recordwork

import android.content.Context
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
 * Editable, JavaRosa-validated but strictly NON-SUBMITTABLE repeat answers
 * for a single permanent UUID row. Never opens native ODK FormFillingActivity,
 * never creates an InstancesRepository record and never accepts source XML.
 *
 * Online READY and exact current target-schema authorization are mandatory
 * before both encrypted read and encrypted save.
 */
class QuarantinedRepeatAnswerActivity : LocalizedActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var settings: SettingsProvider
    private lateinit var status: TextView
    private lateinit var fieldsContainer: LinearLayout
    private lateinit var save: Button
    private lateinit var spinner: ProgressBar
    private val inputs = linkedMapOf<String, EditText>()
    private var generation = 0
    private var openedBinding: QuarantinedWorkDraft? = null
    private var openedFields = emptyList<PrivateScalarField>()

    private val projectId get() = intent.getStringExtra(EXTRA_PROJECT).orEmpty()
    private val workId get() = intent.getStringExtra(EXTRA_WORK).orEmpty()
    private val rowId get() = intent.getStringExtra(EXTRA_ROW).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        title = getString(R.string.private_repeat_answer_title)
        settings = DaggerUtils.getComponent(this).settingsProvider()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        status = TextView(this).apply {
            setText(R.string.private_repeat_answer_loading)
        }
        spinner = ProgressBar(this).apply { visibility = View.GONE }
        save = Button(this).apply {
            setText(R.string.private_repeat_answer_save)
            isEnabled = false
            setOnClickListener { saveCurrent() }
        }
        fieldsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(status)
        root.addView(spinner)
        root.addView(ScrollView(this).apply { addView(fieldsContainer) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        root.addView(save)
        root.addView(TextView(this).apply {
            setText(R.string.private_repeat_answer_no_upload)
        })
        setContentView(root)

        if (projectId.isBlank() || !validUuid(workId) ||
            !validUuid(rowId) || !activeProjectMatches()) {
            deny()
            return
        }
        open()
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
        val fields: List<PrivateScalarField>
    )

    private fun authorize(): Authorized {
        require(activeProjectMatches()) { "Active project changed" }
        val binding = requireNotNull(
            QuarantinedWorkDraftVault(noBackupFilesDir)
                .read(projectId, workId, actorHash())
        ) { "Protected work item not staged" }
        val prepared = RecordWorkPreparationClient(settings)
            .authorizePrivateTextForm(projectId, binding)
        val session = JavaRosaPrivateRepeatAnswerSession(
            cacheDir, binding, prepared.plan, prepared.targetXFormXml
        )
        val ledger = QuarantinedRepeatRowVault(
            noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
        ).read(binding, session.schema)
        val row = requireNotNull(
            ledger.rows.singleOrNull { it.rowId == rowId && !it.isArchived }
        ) { "Repeated row no longer active" }
        val group = requireNotNull(
            session.schema.groups.singleOrNull { it.path == row.groupPath }
        )
        return Authorized(binding, session, group.fields)
    }

    private fun store() = QuarantinedRepeatAnswerStore(
        noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
    )

    private data class Readback(
        val authorized: Authorized,
        val draft: ProtectedRepeatAnswerDraft
    )

    private fun open() {
        save.isEnabled = false
        spinner.visibility = View.VISIBLE
        status.setText(R.string.private_repeat_answer_loading)
        val token = ++generation
        worker.execute {
            val result = runCatching {
                val authorized = authorize()
                val draft = store().read(
                    authorized.binding, authorized.session, rowId
                )
                Readback(authorized, draft)
            }
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed)
                    return@runOnUiThread
                spinner.visibility = View.GONE
                result.onSuccess { render(it) }.onFailure { deny() }
            }
        }
    }

    private fun render(readback: Readback) {
        openedBinding = readback.authorized.binding
        openedFields = readback.authorized.fields
        inputs.clear()
        fieldsContainer.removeAllViews()
        status.text = getString(
            R.string.private_repeat_answer_ready,
            rowId,
            if (readback.draft.hasSavedAnswers)
                getString(R.string.private_repeat_answer_restored)
            else getString(R.string.private_repeat_answer_new)
        )
        readback.authorized.fields.forEach { field ->
            fieldsContainer.addView(TextView(this).apply {
                text = field.path + " (" + field.type.name.lowercase() + ")"
                setPadding(0, 18, 0, 6)
            })
            val input = EditText(this).apply {
                inputType = when (field.type) {
                    PrivateScalarType.TEXT -> InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    PrivateScalarType.INTEGER -> InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
                    PrivateScalarType.DECIMAL -> InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_SIGNED or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL
                    PrivateScalarType.DATE -> InputType.TYPE_CLASS_TEXT
                    else -> error("Unapproved repeated answer type")
                }
                hint = when (field.type) {
                    PrivateScalarType.DATE -> "YYYY-MM-DD"
                    PrivateScalarType.INTEGER -> "Integer"
                    PrivateScalarType.DECIMAL -> "Decimal"
                    else -> ""
                }
                filters = arrayOf(InputFilter.LengthFilter(
                    if (field.type == PrivateScalarType.TEXT)
                        JavaRosaPrivateRepeatAnswerSession.MAX_TEXT else 40
                ))
                setSingleLine(field.type != PrivateScalarType.TEXT)
                setText(readback.draft.values[field.path].orEmpty())
            }
            fieldsContainer.addView(input)
            inputs[field.path] = input
        }
        save.isEnabled = true
    }

    private fun saveCurrent() {
        val previouslyAuthorized = openedBinding ?: return deny()
        if (!activeProjectMatches() || inputs.keys != openedFields.map { it.path }.toSet()) {
            deny()
            return
        }
        val candidate = inputs.mapValues { (_, input) -> input.text.toString() }
        save.isEnabled = false
        spinner.visibility = View.VISIBLE
        status.setText(R.string.private_repeat_answer_saving)
        val token = ++generation
        worker.execute {
            val result = runCatching {
                val fresh = authorize()
                require(fresh.binding == previouslyAuthorized &&
                    fresh.fields == openedFields) {
                    "Repeat answer schema or assignment changed"
                }
                val saved = store().save(
                    fresh.binding, fresh.session, rowId, candidate
                )
                val confirmed = store().read(
                    fresh.binding, fresh.session, rowId
                )
                require(confirmed.hasSavedAnswers &&
                    saved.values == confirmed.values) {
                    "Protected repeated answer readback differed"
                }
            }
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed)
                    return@runOnUiThread
                spinner.visibility = View.GONE
                result.onSuccess {
                    status.setText(R.string.private_repeat_answer_saved)
                    save.isEnabled = true
                }.onFailure {
                    status.setText(R.string.private_repeat_answer_failed)
                    save.isEnabled = false
                }
            }
        }
    }

    private fun deny() {
        openedBinding = null
        openedFields = emptyList()
        inputs.clear()
        fieldsContainer.removeAllViews()
        save.isEnabled = false
        spinner.visibility = View.GONE
        status.setText(R.string.private_repeat_answer_denied)
    }

    override fun onDestroy() {
        generation++
        worker.shutdownNow()
        openedBinding = null
        inputs.clear()
        openedFields = emptyList()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROJECT = "recordwork.repeatanswers.project"
        const val EXTRA_WORK = "recordwork.repeatanswers.work"
        const val EXTRA_ROW = "recordwork.repeatanswers.row"

        fun intentFor(
            context: Context,
            projectId: String,
            workId: String,
            rowId: String
        ): Intent = Intent(context, QuarantinedRepeatAnswerActivity::class.java)
            .putExtra(EXTRA_PROJECT, projectId)
            .putExtra(EXTRA_WORK, workId)
            .putExtra(EXTRA_ROW, rowId)

        private fun validUuid(value: String) =
            runCatching { UUID.fromString(value).toString() == value }
                .getOrDefault(false)
    }
}
