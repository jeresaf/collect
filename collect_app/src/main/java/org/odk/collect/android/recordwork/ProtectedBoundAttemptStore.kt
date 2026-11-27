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
 * No graphical caller yet. Only the dedicated bound sender consumes the
 * verified package through the scoped callback below.
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

    /** Fresh server admission and verified encrypted bytes under the same
     * monitor as all draft edits. No caller-supplied status can grant access.
     * No retry after a local receipt, even if the server reports READY. */
    internal fun withAdmittedContent(b: QuarantinedWorkDraft, expectedAttemptId: String,
                                     expectedWire: String,
                                     consume: (ProtectedBoundAttempt, ProtectedSubmittedReceipt, ByteArray) -> ProtectedTransportResult):
        ProtectedTransportResult = synchronized(ProtectedDraftComponentLock.monitor) {
        require(noReceipt(b))
        val r = readRequest(b)
        val s = readServer(b)
        val admitted = inspect(b)
        require(admitted.state == RemoteProtectedAttemptState.BOUND_EXACT_CONTENT_ADMISSIBLE &&
            admitted.attemptId == expectedAttemptId && admitted.wireSha256 == expectedWire &&
            admitted.attemptId == s.attemptId && admitted.generation == r.generation &&
            admitted.packageSha256 == r.packageSha)
        val local = localReceipt(b, r)
        var result: ProtectedTransportResult? = null
        packages.attemptedPackageEvidence(b, local) { canonical ->
            require(noReceipt(b) && readRequest(b) == r && readServer(b) == s)
            result = consume(admitted, local, canonical)
        }
        requireNotNull(result)
    }

    /** Explicit receipt recovery; requires verified current server completion,
     * never a caller-provided 201 or fabricated status. */
    fun recoverReceipt(b: QuarantinedWorkDraft): ProtectedSubmittedReceipt =
        synchronized(ProtectedDraftComponentLock.monitor) {
            val r = readRequest(b)
            val s = readServer(b)
            val status = inspect(b)
            require(status.state == RemoteProtectedAttemptState.COMPLETED_VERIFIED)
            val receipt = receiptFor(b, r, s, requireNotNull(status.acceptedSubmissionId))
            val values = receiptValues(receipt)
            val target = File(area(b), RECEIPT)
            require(!Files.isSymbolicLink(target.toPath()))
            // A partial receipt is conservatively blocked; never replace it.
            if (!target.exists()) write(area(b), b, RECEIPT, values)
            require(read(b, RECEIPT) == values)
            receipt
        }

    /**
     * Authenticate previously published local evidence without a network call.
     * This is deliberately not a current server confirmation. Never writes,
     * replaces or creates a receipt; no caller can use it to authorize upload.
     */
    fun inspectStoredReceipt(b: QuarantinedWorkDraft): ProtectedSubmittedReceipt =
        synchronized(ProtectedDraftComponentLock.monitor) {
            val r = readRequest(b)
            val s = readServer(b)
            val values = read(b, RECEIPT)
            require(values.size == 7)
            val local = localContent(b, r)
            require(s.projectId == remote.serverProjectId(b) &&
                ProtectedWireCommitment.compute(s.projectId, s.actorId, b.workId,
                    b.sourceSubmissionId, b.targetFormVersionId, s.attemptId,
                    b.reservedInstanceId, b.workType, local) == s.wire)
            val receipt = receiptFor(b, r, s, ProtectedWireCommitment.uuid(values[5]))
            require(receiptValues(receipt) == values && readRequest(b) == r &&
                readServer(b) == s && read(b, RECEIPT) == values &&
                localContent(b, r) == local)
            receipt
        }

    /** Online reopen is strictly read-only, even if a receipt disappears. */
    fun reopenReceipt(b: QuarantinedWorkDraft): ProtectedSubmittedReceipt =
        synchronized(ProtectedDraftComponentLock.monitor) {
            val receipt = inspectStoredReceipt(b)
            val status = inspect(b)
            require(status.state == RemoteProtectedAttemptState.COMPLETED_VERIFIED &&
                status.acceptedSubmissionId == receipt.submissionId &&
                inspectStoredReceipt(b) == receipt)
            receipt
        }

    private fun receiptFor(b: QuarantinedWorkDraft, r: Request, s: Server, child: String) =
        localReceipt(b, r).copy(
            attemptSha256 = QuarantinedPrivateImageStore.digest(
                (r.id + "\n" + s.attemptId + "\n" + s.wire).toByteArray(Charsets.UTF_8)),
            submissionId = child)

    private fun receiptValues(receipt: ProtectedSubmittedReceipt) =
        listOf(receipt.generation, receipt.packageSha256, receipt.ciphertextSha256,
            receipt.attemptSha256, receipt.instanceId, receipt.submissionId, receipt.targetFormVersionId)

    private fun noReceipt(b: QuarantinedWorkDraft): Boolean {
        val file = File(area(b), RECEIPT)
        return !file.exists() && !Files.isSymbolicLink(file.toPath())
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
        // contents happen to match. Bound receipts use a distinct journal.
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
        private const val RECEIPT = "protected-bound-receipt.aesgcm"
    }
}
