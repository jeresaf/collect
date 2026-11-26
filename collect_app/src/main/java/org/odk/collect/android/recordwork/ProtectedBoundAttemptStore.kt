package org.odk.collect.android.recordwork

import org.odk.collect.settings.SettingsProvider
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

/** A verified preparation/status result, not an upload or edit capability. */
internal data class ProtectedBoundAttempt(
    val state: RemoteProtectedAttemptState, val attemptId: String,
    val generation: String, val packageSha256: String, val wireSha256: String,
    val acceptedSubmissionId: String? = null,
    val canUpload: Boolean = false
) {
    init { require(!canUpload) }
}

/**
 * Opt-in U08 handshake seam. Two immutable AES-GCM journals are used:
 * a request marker BEFORE the reservation POST, and a server identity marker
 * BEFORE the binding POST. An ambiguous or partial marker stays blocking.
 * Existing unbound submission-attempt markers are never adopted.
 * No caller in the graphical sender yet; this class never transmits XML/media.
 */
internal class ProtectedBoundAttemptStore(
    private val scratch: File, private val root: File, private val settings: SettingsProvider
) {
    private val cipher = AndroidKeyStorePrivateDraftCipher()
    private val remote = ProtectedAttemptRemoteClient(settings)
    private val packages = ProtectedCommittedDraftStore(scratch, root, settings)
    private val hash = Regex("[0-9a-f]{64}")

    private data class Request(val id: String, val generation: String, val packageSha: String,
                               val ciphertextSha: String)
    private data class Server(val attemptId: String, val actorId: String, val formId: String,
                              val projectId: String, val wire: String)

    /** Explicit start on a fresh, already finalized and authenticated package. */
    fun prepare(b: QuarantinedWorkDraft, generation: String, packageSha: String): ProtectedBoundAttempt =
        synchronized(ProtectedDraftComponentLock.monitor) {
            require(!ProtectedSubmissionAttemptGuard.hasAttempt(root, b))
            ProtectedSubmissionAdmission(scratch, root, settings).inspect(b.projectId, b, generation, packageSha)
            packages.withFinalizedSubmissionContent(b.projectId, b, generation) { persisted, canonical ->
                require(!ProtectedSubmissionAttemptGuard.hasAttempt(root, b) &&
                    persisted.evidence.packaged.packageSha256 == packageSha)
                val request = Request(UUID.randomUUID().toString(), generation, packageSha, persisted.ciphertextSha256)
                val content = ProtectedAttemptedPackageEvidence.parse(canonical, b, localReceipt(b, request))
                // Reject unsupported wire media before reserving work.
                require(content.attachments.all { it.mime in setOf("image/jpeg", "image/png") })
                remote.serverProjectId(b)
                write(area(b), b, REQUEST, listOf(request.id, generation, packageSha, request.ciphertextSha))
            }
            resumePreparation(b)
        }

    /** Explicit resume after reservation/bind response loss; never a new request ID. */
    fun resumePreparation(b: QuarantinedWorkDraft): ProtectedBoundAttempt =
        synchronized(ProtectedDraftComponentLock.monitor) {
            val request = readRequest(b)
            val local = localContent(b, request)
            val serverFile = File(area(b), SERVER)
            require(!Files.isSymbolicLink(serverFile.toPath()))
            val server = if (serverFile.exists()) readServer(b) else {
                val reservation = remote.reserve(b, request.id)
                checkIdentity(b, request, reservation)
                require(reservation.acceptedSubmissionId == null)
                val wire = wire(b, reservation, local)
                require(reservation.wireSha256 == null || reservation.wireSha256 == wire)
                val identity = Server(reservation.attemptId, reservation.actorId, reservation.formId,
                    reservation.projectId, wire)
                write(area(b), b, SERVER, listOf(identity.attemptId, identity.actorId, identity.formId,
                    identity.projectId, identity.wire))
                identity
            }
            var status = remote.status(b, server.attemptId)
            checkStatus(b, request, server, local, status)
            if (status.state == RemoteProtectedAttemptState.RESERVED_UNBOUND) {
                val bound = remote.bind(b, server.attemptId, server.wire)
                checkIdentity(b, request, bound, server)
                require(bound.wireSha256 == server.wire && bound.acceptedSubmissionId == null)
                status = remote.status(b, server.attemptId)
            }
            require(status.state != RemoteProtectedAttemptState.RESERVED_UNBOUND)
            result(b, request, server, local, status)
        }

    /** GET only. No reservation, binding, retry, receipt publication or marker deletion. */
    fun inspect(b: QuarantinedWorkDraft): ProtectedBoundAttempt =
        synchronized(ProtectedDraftComponentLock.monitor) {
            val request = readRequest(b)
            val server = readServer(b)
            val local = localContent(b, request)
            result(b, request, server, local, remote.status(b, server.attemptId))
        }

    private fun result(b: QuarantinedWorkDraft, r: Request, s: Server,
                       local: ProtectedAttemptedPackageEvidence,
                       status: RemoteProtectedAttemptStatus): ProtectedBoundAttempt {
        checkStatus(b, r, s, local, status)
        require(readRequest(b) == r && readServer(b) == s && localContent(b, r) == local)
        return ProtectedBoundAttempt(status.state, s.attemptId, r.generation, r.packageSha,
            s.wire, status.attempt.acceptedSubmissionId)
    }

    private fun checkStatus(b: QuarantinedWorkDraft, r: Request, s: Server,
                            local: ProtectedAttemptedPackageEvidence, status: RemoteProtectedAttemptStatus) {
        val a = status.attempt
        checkIdentity(b, r, a, s)
        require(wire(b, a, local) == s.wire)
        if (status.state != RemoteProtectedAttemptState.RESERVED_UNBOUND) require(a.wireSha256 == s.wire)
        if (status.state == RemoteProtectedAttemptState.COMPLETED_VERIFIED) {
            val child = RemoteRecord(requireNotNull(a.acceptedSubmissionId), a.instanceId, a.formId,
                a.targetVersionId, "")
            val evidence = RemoteCompletionEvidence.parse(requireNotNull(status.completion).toString(), b, child)
            local.requireMatch(evidence)
        }
    }

    private fun checkIdentity(b: QuarantinedWorkDraft, r: Request, a: RemoteProtectedAttempt,
                              s: Server? = null) {
        require(a.projectId == remote.serverProjectId(b) && a.workId == b.workId &&
            a.clientRequestId == r.id && a.sourceId == b.sourceSubmissionId &&
            a.targetVersionId == b.targetFormVersionId && a.instanceId == b.reservedInstanceId)
        if (s != null) require(a.attemptId == s.attemptId && a.actorId == s.actorId &&
            a.formId == s.formId && a.projectId == s.projectId)
    }

    private fun wire(b: QuarantinedWorkDraft, a: RemoteProtectedAttempt, local: ProtectedAttemptedPackageEvidence) =
        ProtectedWireCommitment.compute(a.projectId, a.actorId, b.workId, b.sourceSubmissionId,
            b.targetFormVersionId, a.attemptId, b.reservedInstanceId, b.workType, local)

    private fun localReceipt(b: QuarantinedWorkDraft, r: Request) =
        ProtectedSubmittedReceipt(r.generation, r.packageSha, r.ciphertextSha,
            "0".repeat(64), b.reservedInstanceId, "", b.targetFormVersionId)

    private fun localContent(b: QuarantinedWorkDraft, r: Request): ProtectedAttemptedPackageEvidence {
        remote.serverProjectId(b)
        val area = area(b)
        // Do not upgrade an earlier V1 unbound transmission, even if its
        // contents happen to match. New protocol transmission is not wired yet.
        for (name in listOf("submission-attempt.aesgcm", "submitted-receipt.aesgcm", ".submitted-receipt-write")) {
            val file = File(area, name)
            require(!file.exists() && !Files.isSymbolicLink(file.toPath()))
        }
        return packages.attemptedPackageEvidence(b, localReceipt(b, r))
    }

    private fun area(b: QuarantinedWorkDraft) = ProtectedMultipartSubmission.submissionAttemptArea(root, b)
    private fun aad(b: QuarantinedWorkDraft, name: String) =
        ("DC_BOUND_ATTEMPT_V1\n" + b.projectId + "\n" + b.actorContextSha256 + "\n" +
            b.workId + "\n" + b.reservedInstanceId + "\n" + b.targetFormVersionId + "\n" + name)
            .toByteArray(Charsets.UTF_8)

    private fun write(area: File, b: QuarantinedWorkDraft, name: String, values: List<String>) {
        val file = File(area, name)
        require(!Files.isSymbolicLink(file.toPath()))
        val clear = values.joinToString("\n").toByteArray(Charsets.UTF_8)
        try {
            val sealed = cipher.seal(clear, aad(b, name))
            // A partial exclusive file intentionally blocks every write path.
            require(file.createNewFile())
            FileOutputStream(file).use { it.write(sealed); it.fd.sync() }
            require(read(b, name) == values)
        } finally { clear.fill(0) }
    }

    private fun read(b: QuarantinedWorkDraft, name: String): List<String> {
        val file = File(area(b), name)
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) &&
            file.canonicalFile == file.absoluteFile && file.length() in 30..4096L)
        val clear = cipher.open(file.readBytes(), aad(b, name))
        try { return String(clear, Charsets.UTF_8).split("\n") }
        finally { clear.fill(0) }
    }

    private fun readRequest(b: QuarantinedWorkDraft): Request {
        val v = read(b, REQUEST)
        require(v.size == 4 && v[2].matches(hash) && v[3].matches(hash))
        return Request(ProtectedWireCommitment.uuid(v[0]), ProtectedWireCommitment.uuid(v[1]), v[2], v[3])
    }

    private fun readServer(b: QuarantinedWorkDraft): Server {
        val v = read(b, SERVER)
        require(v.size == 5 && v[4].matches(hash))
        return Server(ProtectedWireCommitment.uuid(v[0]), ProtectedWireCommitment.uuid(v[1]),
            ProtectedWireCommitment.uuid(v[2]), ProtectedWireCommitment.uuid(v[3]), v[4])
    }

    companion object {
        private const val REQUEST = "protected-attempt-request.aesgcm"
        private const val SERVER = "protected-attempt-server.aesgcm"
    }
}
