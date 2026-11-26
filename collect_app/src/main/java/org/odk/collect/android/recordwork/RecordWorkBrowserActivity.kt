package org.odk.collect.android.recordwork

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import org.odk.collect.android.BuildConfig
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.odk.collect.android.R
import org.odk.collect.android.injection.DaggerUtils
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.strings.localization.LocalizedActivity
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * M4B-1 read-only screen for records assigned to the active project/user.
 *
 * Deliberately does not open ODK FormFillingActivity, import source XML, create
 * work items, or submit data: an updated-version mapping UI is still required.
 */
class RecordWorkBrowserActivity : LocalizedActivity() {
    private val background = Executors.newSingleThreadExecutor()
    private val queueWorker = Executors.newSingleThreadExecutor()
    private val statusWorker = Executors.newSingleThreadExecutor()
    private var statusRequest: Future<*>? = null
    private val statusButtons = mutableListOf<Button>()
    private val statusLabels = mutableListOf<TextView>()
    private var requestGeneration = 0
    private lateinit var settingsProvider: SettingsProvider
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var records: LinearLayout
    private lateinit var pending: LinearLayout
    private lateinit var localQueue: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.record_work_browser)
        title = getString(R.string.remote_records_title)
        if (BuildConfig.PROTECTED_BOUND_SUBMISSIONS) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        settingsProvider = DaggerUtils.getComponent(this).settingsProvider()
        progress = findViewById(R.id.remote_records_progress)
        status = findViewById(R.id.remote_records_status)
        records = findViewById(R.id.remote_records_list)
        pending = findViewById(R.id.remote_work_list)
        localQueue = findViewById(R.id.protected_submission_queue)
        localQueue.visibility = if (BuildConfig.PROTECTED_BOUND_SUBMISSIONS) View.VISIBLE else View.GONE
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
        clearLocalQueue()
        progress.visibility = View.GONE
        super.onStop()
    }

    override fun onDestroy() {
        background.shutdownNow()
        queueWorker.shutdownNow()
        statusWorker.shutdownNow()
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
        clearLocalQueue()
        if (session == null) {
            status.setText(R.string.remote_records_changed_project)
            return
        }

        progress.visibility = View.VISIBLE
        status.setText(R.string.remote_records_loading)
        if (BuildConfig.PROTECTED_BOUND_SUBMISSIONS) {
            queueWorker.execute {
                val local = runCatching {
                    require(session.username.isNotBlank())
                    val scope = QuarantinedWorkDraftVault.sha256(session.projectId + "\n" +
                        RecordWorkApiPaths.fromProjectUrl(session.serverUrl).records() + "\n" + session.username)
                    ProtectedSubmissionQueue(noBackupFilesDir).list(session.projectId, scope)
                }
                runOnUiThread {
                    if (generation != requestGeneration || isFinishing || isDestroyed) return@runOnUiThread
                    if (activeSession() != session) {
                        clearLocalQueue()
                        return@runOnUiThread
                    }
                    showLocalQueue(session, local)
                }
            }
        }
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
                    clearLocalQueue()
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
        status.setText(R.string.remote_records_choose_record)
        clearResults()
        if (snapshot.records.isEmpty()) {
            records.addView(text(getString(R.string.remote_records_empty)))
        } else {
            snapshot.records.forEach { record ->
                records.addView(RecordWorkRecordRow.create(this, record) { selected ->
                    val session = activeSession() ?: return@create
                    runCatching {
                        RecordWorkNavigation.record(
                            this@RecordWorkBrowserActivity, session.projectId, selected
                        )
                    }.onSuccess { startActivity(it) }
                })
            }
        }
        if (snapshot.pendingWork.isEmpty()) {
            pending.addView(text(getString(R.string.remote_work_empty)))
        } else {
            snapshot.pendingWork.forEach { task ->
                pending.addView(RecordWorkPendingRow.create(this, task) { selected ->
                    val session = activeSession() ?: return@create
                    runCatching {
                        RecordWorkNavigation.pending(
                            this@RecordWorkBrowserActivity, session.projectId, selected
                        )
                    }.onSuccess { startActivity(it) }
                })
            }
        }
    }

    private fun showLocalQueue(session: Session, result: Result<List<ProtectedSubmissionQueue.Entry>>) {
        clearLocalQueue()
        localQueue.addView(text(getString(R.string.private_queue_title)))
        localQueue.addView(text(getString(R.string.private_queue_warning)))
        result.fold(onSuccess = { entries ->
            if (entries.isEmpty()) localQueue.addView(text(getString(R.string.private_queue_empty)))
            entries.forEach { entry ->
                val label = when (entry.state) {
                    ProtectedSubmissionQueue.State.CHECKPOINT_RECHECK -> R.string.private_queue_checkpoint
                    ProtectedSubmissionQueue.State.FINALIZATION_RECHECK -> R.string.private_queue_finalization
                    ProtectedSubmissionQueue.State.LEGACY_UNCONFIRMED -> R.string.private_queue_legacy
                    ProtectedSubmissionQueue.State.BOUND_STATUS_REQUIRED -> R.string.private_queue_bound
                    ProtectedSubmissionQueue.State.RECEIPT_RECHECK -> R.string.private_queue_receipt
                    ProtectedSubmissionQueue.State.UNAVAILABLE -> R.string.private_queue_unavailable
                }
                localQueue.addView(Button(this).apply {
                    text = getString(R.string.private_queue_row, entry.workId, getString(label))
                    isAllCaps = false
                    isEnabled = entry.state != ProtectedSubmissionQueue.State.UNAVAILABLE
                    setOnClickListener {
                        if (activeSession() == session) {
                            startActivity(QuarantinedCombinedDraftActivity.intentFor(
                                this@RecordWorkBrowserActivity, session.projectId, entry.workId))
                        } else {
                            refresh()
                        }
                    }
                })
                if (entry.boundStatusCheck) {
                    val evidence = TextView(this).apply {
                        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    }
                    val check = Button(this).apply {
                        setText(R.string.private_queue_check_status)
                        contentDescription = getString(R.string.private_queue_check_item, entry.workId)
                        isAllCaps = false
                        setOnClickListener { checkQueueStatus(session, entry, evidence) }
                    }
                    statusLabels.add(evidence)
                    statusButtons.add(check)
                    localQueue.addView(check)
                    localQueue.addView(evidence)
                }
            }
        }, onFailure = {
            localQueue.addView(text(getString(R.string.private_queue_unavailable)))
        })
    }

    private fun clearLocalQueue() {
        statusRequest?.cancel(true)
        statusRequest = null
        statusButtons.clear()
        statusLabels.clear()
        localQueue.removeAllViews()
    }

    private fun checkQueueStatus(session: Session, entry: ProtectedSubmissionQueue.Entry, label: TextView) {
        if (activeSession() != session) {
            refresh()
            return
        }
        // At most one check in this browser generation. Old results never render.
        if (statusRequest != null) return
        val generation = requestGeneration
        statusButtons.forEach { it.isEnabled = false }
        statusLabels.forEach { it.text = "" }
        label.setText(R.string.private_queue_checking)
        statusRequest = statusWorker.submit {
            val result = runCatching {
                val scope = QuarantinedWorkDraftVault.sha256(session.projectId + "\n" +
                    RecordWorkApiPaths.fromProjectUrl(session.serverUrl).records() + "\n" + session.username)
                val store = ProtectedBoundAttemptStore(cacheDir, noBackupFilesDir, settingsProvider)
                ProtectedQueueStatusReader(noBackupFilesDir, store::inspect)
                    .check(session.projectId, scope, entry.workId)
            }.getOrDefault(ProtectedQueueStatusReader.Status.UNAVAILABLE)
            runOnUiThread {
                if (generation != requestGeneration || isFinishing || isDestroyed) return@runOnUiThread
                if (activeSession() != session) {
                    clearLocalQueue()
                    return@runOnUiThread
                }
                statusRequest = null
                statusButtons.forEach { it.isEnabled = true }
                label.setText(when (result) {
                    ProtectedQueueStatusReader.Status.PREPARATION_INCOMPLETE -> R.string.private_queue_server_unbound
                    ProtectedQueueStatusReader.Status.EXACT_CONTENT_ADMISSIBLE -> R.string.private_queue_server_admissible
                    ProtectedQueueStatusReader.Status.SERVER_COMPLETED -> R.string.private_queue_server_completed
                    ProtectedQueueStatusReader.Status.ACCESS_UNVERIFIED -> R.string.private_queue_server_denied
                    ProtectedQueueStatusReader.Status.CONFLICT -> R.string.private_queue_server_conflict
                    ProtectedQueueStatusReader.Status.UNAVAILABLE -> R.string.private_queue_server_unknown
                })
            }
        }
    }

    private fun text(value: String) = TextView(this).apply {
        text = value
        setPadding(0, 8, 0, 16)
    }

    private fun clearResults() {
        records.removeAllViews()
        pending.removeAllViews()
    }

    private data class Session(
        val projectId: String,
        val serverUrl: String,
        val username: String
    )
}
