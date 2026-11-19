package org.odk.collect.android.recordwork

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import org.odk.collect.android.R
import org.odk.collect.android.injection.DaggerUtils
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.strings.localization.LocalizedActivity
import java.util.concurrent.Executors

/**
 * M4B-1 read-only screen for records assigned to the active project/user.
 *
 * Deliberately does not open ODK FormFillingActivity, import source XML, create
 * work items, or submit data: an updated-version mapping UI is still required.
 */
class RecordWorkBrowserActivity : LocalizedActivity() {
    private val background = Executors.newSingleThreadExecutor()
    private var requestGeneration = 0
    private lateinit var settingsProvider: SettingsProvider
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var records: TextView
    private lateinit var pending: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.record_work_browser)
        title = getString(R.string.remote_records_title)

        settingsProvider = DaggerUtils.getComponent(this).settingsProvider()
        progress = findViewById(R.id.remote_records_progress)
        status = findViewById(R.id.remote_records_status)
        records = findViewById(R.id.remote_records_list)
        pending = findViewById(R.id.remote_work_list)
        findViewById<Button>(R.id.refresh_remote_records).setOnClickListener { refresh() }
        clearResults()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        // A project switch or background transition must not render stale data.
        requestGeneration++
        clearResults()
        progress.visibility = View.GONE
        super.onStop()
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    private fun activeSession(): Session? {
        val project = DaggerUtils.getComponent(this)
            .currentProjectProvider().getCurrentProject().value ?: return null
        val settings = settingsProvider.getUnprotectedSettings(project.uuid)
        return Session(
            project.uuid,
            settings.getString(ProjectKeys.KEY_SERVER_URL).orEmpty(),
            settings.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        )
    }

    private fun refresh() {
        val session = activeSession()
        val generation = ++requestGeneration
        clearResults()
        if (session == null) {
            status.setText(R.string.remote_records_changed_project)
            return
        }

        progress.visibility = View.VISIBLE
        status.setText(R.string.remote_records_loading)
        background.execute {
            val result = try {
                Result.success(RecordWorkReadOnlyClient(settingsProvider).load(session.projectId))
            } catch (error: ReadOnlyRecordException) {
                Result.failure(error)
            } catch (_: Exception) {
                Result.failure(ReadOnlyRecordException(ReadOnlyRequestFailure.NETWORK_ERROR))
            }
            runOnUiThread {
                if (generation != requestGeneration || isFinishing || isDestroyed) return@runOnUiThread
                if (activeSession() != session) {
                    clearResults()
                    status.setText(R.string.remote_records_changed_project)
                    progress.visibility = View.GONE
                    return@runOnUiThread
                }
                progress.visibility = View.GONE
                result.fold(
                    onSuccess = { snapshot -> show(snapshot) },
                    onFailure = { error ->
                        clearResults()
                        status.setText(
                            when ((error as? ReadOnlyRecordException)?.reason) {
                                ReadOnlyRequestFailure.INVALID_PROJECT -> R.string.remote_records_invalid_project
                                ReadOnlyRequestFailure.NO_CREDENTIALS -> R.string.remote_records_missing_credentials
                                ReadOnlyRequestFailure.AUTH_REQUIRED -> R.string.remote_records_auth_required
                                ReadOnlyRequestFailure.ACCESS_DENIED -> R.string.remote_records_forbidden
                                ReadOnlyRequestFailure.NOT_SUPPORTED -> R.string.remote_records_not_supported
                                ReadOnlyRequestFailure.REDIRECT_REFUSED -> R.string.remote_records_redirect_refused
                                ReadOnlyRequestFailure.INVALID_RESPONSE -> R.string.remote_records_invalid_response
                                ReadOnlyRequestFailure.SERVER_ERROR -> R.string.remote_records_server_error
                                else -> R.string.remote_records_network_error
                            }
                        )
                    }
                )
            }
        }
    }

    private fun show(snapshot: RemoteRecordSnapshot) {
        status.setText(R.string.remote_records_read_only)
        records.text = if (snapshot.records.isEmpty()) {
            getString(R.string.remote_records_empty)
        } else {
            snapshot.records.joinToString("\n\n") {
                "${it.submittedAt}\n${it.submissionId}\nForm: ${it.formId} (${it.formVersionId})"
            }
        }
        pending.text = if (snapshot.pendingWork.isEmpty()) {
            getString(R.string.remote_work_empty)
        } else {
            snapshot.pendingWork.joinToString("\n\n") {
                "${it.type} ${it.visitKey.orEmpty()}\n${it.id}\nSource: ${it.sourceSubmissionId}"
            }
        }
    }

    private fun clearResults() {
        records.text = ""
        pending.text = ""
    }

    private data class Session(
        val projectId: String,
        val serverUrl: String,
        val username: String
    )
}
