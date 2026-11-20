package org.odk.collect.android.recordwork

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Values are encrypted before any filesystem write. The AES-GCM associated
 * data pins the exact server work, user/project context, form version, and
 * source record. No unencrypted instance XML, XLSForm, answers, media or
 * credentials are stored. This is NEVER an ODK InstancesRepository draft.
 */
interface PrivateDraftCipher {
    fun seal(cleartext: ByteArray, associatedData: ByteArray): ByteArray
    fun open(envelope: ByteArray, associatedData: ByteArray): ByteArray
}

/** Android Keystore key is non-exportable and tied to this app installation. */
class AndroidKeyStorePrivateDraftCipher : PrivateDraftCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    @Synchronized
    override fun seal(cleartext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData)
        val encrypted = cipher.doFinal(cleartext)
        return ByteArrayOutputStream().apply {
            write(VERSION)
            write(cipher.iv.size)
            write(cipher.iv)
            write(encrypted)
        }.toByteArray()
    }

    @Synchronized
    override fun open(envelope: ByteArray, associatedData: ByteArray): ByteArray {
        require(envelope.size in 30..MAX_ENVELOPE && envelope[0] == VERSION.toByte() &&
            envelope[1].toInt() == GCM_IV_BYTES) {
            "Corrupt encrypted draft envelope"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = envelope.copyOfRange(2, 2 + GCM_IV_BYTES)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(envelope.copyOfRange(2 + GCM_IV_BYTES, envelope.size))
    }

    companion object {
        private const val ALIAS = "org.jeresaf.datacollector.recordwork.v1"
        private const val VERSION = 1
        private const val GCM_IV_BYTES = 12
        private const val MAX_ENVELOPE = 2_200_000
    }
}

data class PrivateTextDraftValues(
    val answers: Map<String, String>,
    val hasSavedDraft: Boolean,
    val canFinalize: Boolean = false,
    val canSubmit: Boolean = false
) {
    init {
        require(!canFinalize && !canSubmit) {
            "Private text draft is not an uploadable ODK Instance"
        }
    }
}

/**
 * The active vault binding must be read from the private vault with the exact
 * active project and user-scope hash before every save/reopen. Revalidation
 * against the LIVE server is the caller's responsibility before this method.
 */
class EncryptedPrivateTextDraftStore(
    private val noBackupRoot: File,
    private val cipher: PrivateDraftCipher,
    private val vault: QuarantinedWorkDraftVault =
        QuarantinedWorkDraftVault(noBackupRoot)
) {
    @Synchronized
    fun save(
        binding: QuarantinedWorkDraft,
        validated: Map<String, String>,
        allowedPaths: List<String>
    ) {
        verifyBinding(binding)
        require(allowedPaths.isNotEmpty() &&
            allowedPaths.size <= JavaRosaPrivateTextSession.MAX_FIELDS &&
            allowedPaths.toSet().size == allowedPaths.size &&
            validated.keys == allowedPaths.toSet() &&
            validated.values.all { it.length <= JavaRosaPrivateTextSession.MAX_TEXT }) {
            "Draft answers differ from the JavaRosa-validated target paths"
        }
        val file = answerFile(binding)
        require(file.parentFile?.isDirectory == true &&
            !java.nio.file.Files.isSymbolicLink(file.parentFile.toPath()) &&
            !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            "Unsafe protected draft directory"
        }
        // Canonical form field order from the server-validated native session
        // is included in the ciphertext and checked again on read.
        val clear = pack(allowedPaths, validated)
        try {
            val sealed = cipher.seal(clear, aad(binding))
            require(sealed.isNotEmpty() && sealed.size <= MAX_FILE) {
                "Encrypted draft exceeds limit"
            }
            val pending = File(file.parentFile, ".answers-" +
                java.util.UUID.randomUUID().toString())
            try {
                FileOutputStream(pending).use { out ->
                    out.write(sealed)
                    out.fd.sync()
                }
                // Atomic replacement on Android's app-private filesystem.
                // Never expose a half-written ciphertext as valid state.
                if (!pending.renameTo(file)) {
                    require(file.delete() && pending.renameTo(file)) {
                        "Unable to atomically store protected answers"
                    }
                }
            } finally {
                if (pending.exists()) pending.delete()
            }
        } finally {
            clear.fill(0)
        }
    }

    @Synchronized
    fun read(
        binding: QuarantinedWorkDraft,
        allowedPaths: List<String>
    ): PrivateTextDraftValues {
        verifyBinding(binding)
        require(allowedPaths.isNotEmpty() &&
            allowedPaths.size <= JavaRosaPrivateTextSession.MAX_FIELDS &&
            allowedPaths.toSet().size == allowedPaths.size) {
            "Invalid authorized question inventory"
        }
        val file = answerFile(binding)
        if (!file.exists()) {
            return PrivateTextDraftValues(emptyMap(), hasSavedDraft = false)
        }
        require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
            file.length() in 30..MAX_FILE.toLong()) {
            "Invalid private encrypted draft"
        }
        val clear = cipher.open(file.readBytes(), aad(binding))
        try {
            val answers = unpack(clear)
            require(answers.keys == allowedPaths.toSet()) {
                "Draft form inventory differs from its saved version"
            }
            return PrivateTextDraftValues(answers, hasSavedDraft = true)
        } finally {
            clear.fill(0)
        }
    }

    private fun verifyBinding(binding: QuarantinedWorkDraft) {
        val current = requireNotNull(vault.read(
            binding.projectId, binding.workId, binding.actorContextSha256
        )) { "Work-item binding is not present on this device" }
        require(current == binding) { "Work-item context or identity changed" }
        require(binding.state == QuarantinedWorkDraft.STATE_QUARANTINED &&
            !binding.canOpenEditor && !binding.canFinalize && !binding.canUpload) {
            "Unapproved draft state"
        }
    }

    private fun answerFile(binding: QuarantinedWorkDraft): File {
        val directory = File(File(File(noBackupRoot,
            QuarantinedWorkDraftVault.VAULT_DIRECTORY),
            binding.projectId), binding.workId)
        require(directory.canonicalFile.parentFile?.name == binding.projectId &&
            directory.canonicalFile.parentFile?.parentFile?.name ==
                QuarantinedWorkDraftVault.VAULT_DIRECTORY) {
            "Draft directory escapes authorized work"
        }
        return File(directory, "private-answers.aesgcm")
    }

    private fun aad(b: QuarantinedWorkDraft): ByteArray = listOf(
        "DC_PRIVATE_TEXT_DRAFT_V1",
        b.projectId, b.actorContextSha256, b.workId, b.sourceSubmissionId,
        b.sourceInstanceId, b.targetFormVersionId, b.targetFormId,
        b.targetVersion, b.workType.name, b.visitKey.orEmpty(),
        b.reservedInstanceId, b.sourceXmlSha256, b.sourceXFormSha256.orEmpty(),
        b.targetXFormSha256, b.reviewedPaths.toString(),
        b.validationTransfers.toString(), b.requiresDeprecatedId.toString()
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun pack(order: List<String>, values: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(1)
            out.writeInt(order.size)
            for (path in order) {
                bytes(out, path)
                bytes(out, requireNotNull(values[path]))
            }
        }
        require(output.size() <= MAX_PLAINTEXT)
        return output.toByteArray()
    }

    private fun unpack(clear: ByteArray): Map<String, String> {
        require(clear.size in 8..MAX_PLAINTEXT)
        val input = DataInputStream(ByteArrayInputStream(clear))
        require(input.readInt() == 1)
        val count = input.readInt()
        require(count in 1..JavaRosaPrivateTextSession.MAX_FIELDS)
        val answers = linkedMapOf<String, String>()
        repeat(count) {
            val path = readBytes(input, 512)
            val answer = readBytes(input, 65536)
            require(answer.length <= JavaRosaPrivateTextSession.MAX_TEXT &&
                path !in answers) { "Corrupt or ambiguous encrypted answer" }
            answers[path] = answer
        }
        require(input.available() == 0) { "Trailing draft data" }
        return answers
    }

    private fun bytes(out: DataOutputStream, value: String) {
        val b = value.toByteArray(Charsets.UTF_8)
        require(b.size <= 65536)
        out.writeInt(b.size)
        out.write(b)
    }

    private fun readBytes(input: DataInputStream, limit: Int): String {
        val size = input.readInt()
        require(size in 0..limit && size <= input.available())
        val data = ByteArray(size)
        input.readFully(data)
        return String(data, Charsets.UTF_8)
    }

    companion object {
        private const val MAX_PLAINTEXT = 2_100_000
        private const val MAX_FILE = 2_200_000
    }
}
