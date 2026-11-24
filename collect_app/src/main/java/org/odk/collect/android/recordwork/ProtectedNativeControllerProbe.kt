package org.odk.collect.android.recordwork

import org.javarosa.core.model.Constants
import org.javarosa.core.model.FormDef
import org.javarosa.core.model.FormIndex
import org.javarosa.core.model.GroupDef
import org.javarosa.core.model.IFormElement
import org.javarosa.core.model.QuestionDef
import org.javarosa.core.model.data.StringData
import org.javarosa.core.model.data.IntegerData
import org.odk.collect.android.javarosawrapper.FailedValidationResult
import org.javarosa.core.model.instance.InstanceInitializationFactory
import org.javarosa.core.model.instance.TreeReference
import org.javarosa.form.api.FormEntryController
import org.javarosa.form.api.FormEntryModel
import org.odk.collect.android.formentry.FormDefCache
import org.odk.collect.android.formentry.FormEntryUseCases
import org.odk.collect.android.javarosawrapper.JavaRosaFormController
import org.odk.collect.settings.SettingsProvider
import java.io.File
import java.util.UUID

/**
 * First native editor integration boundary: authorize and inspect the actual
 * ODK JavaRosaFormController in an isolated in-memory session. The controller
 * never escapes this method, has no instance file and is never registered in
 * FormsRepository or InstancesRepository. No ordinary FormFillingActivity,
 * save, finalize, attachment capture or upload is permitted by this probe.
 */
internal data class NativeRepeatExercise(
    val groupPath: String,
    val rows: List<Map<String, String>>,
    val editedFirstPath: String,
    val editedFirstValue: String
)

internal data class ProtectedNativeControllerEvidence(
    val workId: String,
    val targetFormVersionId: String,
    val targetXFormSha256: String,
    val questionPaths: Set<String>,
    val imagePaths: Set<String>,
    val repeatPaths: Set<String>,
    val firstQuestionPath: String,
    val inMemoryAnswerVerified: Boolean = false,
    val nextQuestionPath: String? = null,
    val inMemoryRepeatVerified: Boolean = false,
    val repeatRowsBeforeDeletion: Int = 0,
    val repeatRowsAfterDeletion: Int = 0,
    val conditionalValidationVerified: Boolean = false,
    val directImagePath: String? = null,
    val directImageReceipt: ProtectedImageReceipt? = null,
    val nativeImageReferenceVerified: Boolean = false,
    val canSave: Boolean = false,
    val canFinalize: Boolean = false,
    val canUpload: Boolean = false
) {
    init {
        require(!canSave && !canFinalize && !canUpload)
    }
}

internal class ProtectedNativeControllerProbe(
    private val scratchRoot: File,
    private val noBackupRoot: File,
    private val settings: SettingsProvider
) {
    fun inspect(
        projectId: String,
        binding: QuarantinedWorkDraft,
        firstTextAnswer: String? = null,
        repeatExercise: NativeRepeatExercise? = null,
        conditionalExercise: Boolean = false,
        directImagePath: String? = null
    ): ProtectedNativeControllerEvidence {
        // This is a disposable controller exercise, not a draft save API.
        require(firstTextAnswer == null ||
            (firstTextAnswer.isNotBlank() && firstTextAnswer.length <= 16_384 &&
                '\u0000' !in firstTextAnswer)) { "Invalid temporary native text answer" }
        require(!conditionalExercise || (firstTextAnswer == null && repeatExercise == null && directImagePath == null))
        require(directImagePath == null || (firstTextAnswer == null && repeatExercise == null))
        require(repeatExercise == null || firstTextAnswer != null) {
            "Repeat exercise requires the direct text navigation gate"
        }
        require(binding.projectId == projectId &&
            binding.workType == RecordWorkType.FOLLOW_UP &&
            binding.validationTransfers == 0 &&
            !binding.canOpenEditor && !binding.canFinalize && !binding.canUpload)
        val authorized = RecordWorkPreparationClient(settings)
            .authorizePrivateTextForm(projectId, binding)
        val plan = authorized.plan
        val xml = authorized.targetXFormXml
        val approval = requireNotNull(ProtectedMappingApprovalStore(
            noBackupRoot, AndroidKeyStorePrivateDraftCipher()
        ).read(binding, plan)) { "Encrypted complete mapping approval required" }
        require(approval.decisions.values.all {
            it == MappingDecision.REENTER || it == MappingDecision.LEAVE_EMPTY
        }) { "Independent follow-up must not transfer historical answers" }
        require(JavaRosaPrivateScalarSession.digest(xml) == binding.targetXFormSha256)
        // No external resources/actions in this initial controller boundary.
        RecordWorkSafeXml.parse(xml, 8_388_608, 64, 20_000)
        require(!EXTERNAL_SRC.containsMatchIn(xml) &&
            !UNSUPPORTED_ACTION.containsMatchIn(xml)) {
            "External resources or active form actions need separate qualification"
        }
        require(scratchRoot.isDirectory || scratchRoot.mkdirs())
        val folder = File(scratchRoot, "recordwork-native-controller-" + UUID.randomUUID())
        require(folder.mkdir())
        try {
            val formFile = File(folder, "target.xml")
            formFile.writeText(xml, Charsets.UTF_8)
            val noCache = object : FormDefCache {
                override fun readCache(formXml: File?): FormDef? = null
                override fun writeCache(formDef: FormDef?, formPath: String?) = Unit
            }
            val def = requireNotNull(FormEntryUseCases.createFormDefFromCacheOrXml(formFile, noCache))
            val entry = FormEntryController(FormEntryModel(def))
            def.initialize(true, InstanceInitializationFactory())
            val controller = JavaRosaFormController(folder, entry, null)
            require(controller.getInstanceFile() == null && !controller.isEditing())
            val questionPaths = linkedSetOf<String>()
            val imagePaths = linkedSetOf<String>()
            val repeatPaths = linkedSetOf<String>()
            fun absolutePath(ref: TreeReference): String {
                require(ref.size() in 2..12 &&
                    ref.getName(0) == def.instance.root.name)
                return "/" + (0 until ref.size()).joinToString("/") { ref.getName(it) }
            }
            fun visit(elements: List<IFormElement>?, depth: Int) {
                require(depth <= 12)
                elements.orEmpty().forEach { element ->
                    when (element) {
                        is QuestionDef -> {
                            val ref = element.bind?.reference as? TreeReference
                                ?: throw IllegalArgumentException("Unbound native question")
                            val path = absolutePath(ref)
                            require(questionPaths.add(path) && questionPaths.size <= 30)
                            if (element.controlType == Constants.CONTROL_IMAGE_CHOOSE) {
                                imagePaths.add(path)
                            }
                        }
                        is GroupDef -> {
                            if (element.getRepeat()) {
                                val ref = element.bind?.reference as? TreeReference
                                    ?: throw IllegalArgumentException("Unbound native repeat")
                                require(repeatPaths.add(absolutePath(ref)) && repeatPaths.size <= 10)
                            }
                            visit(element.children, depth + 1)
                        }
                        else -> throw IllegalArgumentException("Unsupported native control")
                    }
                }
            }
            val nativeForm = requireNotNull(controller.getFormDef()) {
                "Native ODK controller did not retain its initialized form"
            }
            require(nativeForm === def) { "Native controller form identity changed" }
            visit(nativeForm.children, 0)
            require(questionPaths == plan.candidates.map { it.targetPath }.toSet()) {
                "Native questionnaire differs from explicitly reviewed target paths"
            }
            require(imagePaths == plan.candidates.filter { MappingRisk.MEDIA in it.risks }
                .map { it.targetPath }.toSet()) { "Unqualified native media controls" }
            require(plan.candidates.filter { MappingRisk.REPEAT in it.risks }.all { candidate ->
                repeatPaths.any { candidate.targetPath.startsWith(it + "/") }
            }) { "Native repeat inventory differs from review" }
            // Exercise the actual ODK controller's navigation to the first
            // question without generating instance XML.
            var first: String? = null
            for (attempt in 1..100) {
                val event = controller.stepToNextEvent(true)
                if (event == FormEntryController.EVENT_QUESTION) {
                    first = absolutePath(controller.getFormIndex().reference)
                    break
                }
                if (event == FormEntryController.EVENT_END_OF_FORM) break
            }
            require(first != null && first in questionPaths)
            var answerVerified = false
            var next: String? = null
            if (firstTextAnswer != null) {
                // A direct scalar REENTER question only. No historical
                // transfer, repeat mutation or media capture in this gate.
                require(approval.decisions[first] == MappingDecision.REENTER &&
                    first !in imagePaths &&
                    repeatPaths.none { first.startsWith(it + "/") })
                val index = controller.getFormIndex()
                val ref = index.reference
                val node = requireNotNull(def.instance.resolveReference(ref))
                require(node.isLeaf && node.dataType == Constants.DATATYPE_TEXT)
                require(controller.getAnswer(ref)?.value == null ||
                    controller.getAnswer(ref)?.value == "") {
                    "Independent visit must not contain a historical answer"
                }
                val result = controller.answerQuestion(index, StringData(firstTextAnswer))
                require(result == FormEntryController.ANSWER_OK &&
                    controller.getAnswer(ref)?.value == firstTextAnswer) {
                    "Native ODK controller rejected or changed the entered answer"
                }
                // Navigate to the next genuine question, then return to the
                // exact first native reference and prove the answer survived.
                for (attempt in 1..100) {
                    val event = controller.stepToNextEvent(true)
                    if (event == FormEntryController.EVENT_QUESTION) {
                        next = absolutePath(controller.getFormIndex().reference)
                        break
                    }
                    if (event == FormEntryController.EVENT_END_OF_FORM) break
                }
                require(next != null && next != first && next in questionPaths)
                var returned = false
                for (attempt in 1..100) {
                    val event = controller.stepToPreviousEvent()
                    if (event == FormEntryController.EVENT_QUESTION &&
                        controller.getFormIndex().reference == ref) {
                        returned = true
                        break
                    }
                    if (event == FormEntryController.EVENT_BEGINNING_OF_FORM) break
                }
                require(returned && controller.getAnswer(ref)?.value == firstTextAnswer) {
                    "Native navigation did not retain the entered answer"
                }
                answerVerified = true
            }
            var repeatVerified = false
            var beforeDelete = 0
            var afterDelete = 0
            if (repeatExercise != null) {
                val group = repeatExercise.groupPath
                val parts = group.removePrefix("/").split("/")
                require(repeatPaths == setOf(group) && parts.size == 2 &&
                    parts.first() == def.instance.root.name) {
                    "Only one directly bound top-level repeat is qualified"
                }
                val fields = questionPaths.filter { it.startsWith(group + "/") }.toSet()
                require(fields.isNotEmpty() && fields.size <= 12 &&
                    fields.all { path ->
                        path.removePrefix(group + "/").let { it.isNotBlank() && '/' !in it } &&
                            path !in imagePaths &&
                            approval.decisions[path] == MappingDecision.REENTER
                    }) { "Repeat exercise requires reviewed direct text fields" }
                val rows = repeatExercise.rows
                fun valid(value: String): Boolean =
                    value.isNotBlank() && value.length <= 16_384 && '\u0000' !in value
                require(rows.size == 2 && rows.all { row ->
                    row.keys == fields && row.values.all(::valid)
                } && fields.all { rows[0][it] != rows[1][it] } &&
                    repeatExercise.editedFirstPath in fields &&
                    valid(repeatExercise.editedFirstValue) &&
                    repeatExercise.editedFirstValue != rows[0][repeatExercise.editedFirstPath] &&
                    repeatExercise.editedFirstValue != rows[1][repeatExercise.editedFirstPath])
                fun rowExists(row: Int): Boolean =
                    def.instance.root.getChild(parts[1], row) != null
                fun fieldRef(path: String, row: Int): TreeReference =
                    TreeReference.rootRef().apply {
                        add(parts[0], 0)
                        add(parts[1], row)
                        add(path.substringAfterLast('/'), 0)
                    }
                fun assertRows(expected: List<Map<String, String>>) {
                    require(expected.indices.all(::rowExists) && !rowExists(expected.size)) {
                        "Native repeat row count differs from expected"
                    }
                    expected.forEachIndexed { row, answers ->
                        answers.forEach { (path, value) ->
                            require(controller.getAnswer(fieldRef(path, row))?.value == value) {
                                "Native repeat answer identity changed"
                            }
                        }
                    }
                }
                // Native initialization may start with a blank row or with
                // no concrete row, depending on the exact XForm template.
                require(!rowExists(1)) { "Unexpected pre-existing repeat rows" }
                val indices = linkedMapOf<Pair<Int, String>, FormIndex>()
                var created = 0
                for (attempt in 1..300) {
                    val event = controller.stepToNextEvent(true)
                    val index = controller.getFormIndex()
                    if (event == FormEntryController.EVENT_PROMPT_NEW_REPEAT) {
                        require(absolutePath(index.reference) == group)
                        if (indices.size == rows.size * fields.size) break
                        require(created < 2 && !rowExists(1)) {
                            "Native repeat creation exceeds the exercise bound"
                        }
                        controller.newRepeat()
                        created++
                        continue
                    }
                    if (event == FormEntryController.EVENT_END_OF_FORM) break
                    if (event != FormEntryController.EVENT_QUESTION) continue
                    val path = absolutePath(index.reference)
                    if (path !in fields) continue
                    val row = index.reference.getMultiplicity(1)
                    require(row in rows.indices)
                    val key = row to path
                    require(key !in indices) { "Duplicate native repeat field navigation" }
                    val node = requireNotNull(def.instance.resolveReference(index.reference))
                    require(node.isLeaf && node.dataType == Constants.DATATYPE_TEXT)
                    require(controller.getAnswer(index.reference)?.value.let {
                        it == null || it == ""
                    }) { "New repeat row must start without historical values" }
                    val value = requireNotNull(rows[row][path])
                    require(controller.answerQuestion(index, StringData(value)) ==
                        FormEntryController.ANSWER_OK)
                    indices[key] = index
                    if (indices.size == rows.size * fields.size) break
                }
                require(created in 1..2 && indices.size == rows.size * fields.size)
                assertRows(rows)
                beforeDelete = rows.size

                val editIndex = requireNotNull(indices[0 to repeatExercise.editedFirstPath])
                require(controller.jumpToIndex(editIndex) == FormEntryController.EVENT_QUESTION)
                require(controller.answerQuestion(editIndex, StringData(repeatExercise.editedFirstValue)) ==
                    FormEntryController.ANSWER_OK)
                val edited = rows[0] + (repeatExercise.editedFirstPath to repeatExercise.editedFirstValue)
                assertRows(listOf(edited, rows[1]))

                // The ordinary ODK delete-repeat dialog invokes this same
                // native operation from the current repeat question index.
                val deleteIndex = requireNotNull(indices[1 to repeatExercise.editedFirstPath])
                require(controller.jumpToIndex(deleteIndex) == FormEntryController.EVENT_QUESTION)
                controller.deleteRepeat()
                assertRows(listOf(edited))
                afterDelete = 1
                val directRef = TreeReference.rootRef().apply {
                    add(parts[0], 0)
                    add(first.substringAfterLast('/'), 0)
                }
                require(controller.getAnswer(directRef)?.value == firstTextAnswer)
                repeatVerified = true
            }
            var conditionalVerified = false
            if (conditionalExercise) {
                // A deliberately narrow qualification of direct native
                // relevance, required/constraint validation and calculation.
                // No save, serialization or form action is performed.
                val gate = "/data/gate"
                val count = "/data/count"
                val total = "/data/total"
                require(first == gate && questionPaths == setOf(gate, count, total) &&
                    repeatPaths.isEmpty() && imagePaths.isEmpty() &&
                    approval.decisions.values.all { it == MappingDecision.REENTER })
                val byPath = plan.candidates.associateBy { it.targetPath }
                require(MappingRisk.REQUIRED in requireNotNull(byPath[gate]).risks &&
                    MappingRisk.RELEVANCE_OR_CONSTRAINT in requireNotNull(byPath[count]).risks &&
                    MappingRisk.CALCULATION in requireNotNull(byPath[total]).risks)
                val gateIndex = controller.getFormIndex()
                fun ref(path: String) = TreeReference.rootRef().apply {
                    path.removePrefix("/").split("/").forEach { add(it, 0) }
                }
                fun nextQuestion(): String? {
                    for (attempt in 1..100) {
                        val event = controller.stepToNextEvent(true)
                        if (event == FormEntryController.EVENT_QUESTION)
                            return absolutePath(controller.getFormIndex().reference)
                        if (event == FormEntryController.EVENT_END_OF_FORM) return null
                    }
                    throw IllegalArgumentException("Native conditional navigation exceeded bound")
                }
                fun setGate(value: String) {
                    require(controller.jumpToIndex(gateIndex) == FormEntryController.EVENT_QUESTION)
                    require(controller.answerQuestion(gateIndex, StringData(value)) ==
                        FormEntryController.ANSWER_OK)
                }
                require(controller.validateAnswers(false) is FailedValidationResult) {
                    "Blank required gate must prevent validation"
                }
                require(controller.answerQuestion(gateIndex, StringData("invalid")) ==
                    FormEntryController.ANSWER_CONSTRAINT_VIOLATED)
                setGate("yes")
                require(nextQuestion() == count)
                val countIndex = controller.getFormIndex()
                require(controller.validateAnswers(false) is FailedValidationResult) {
                    "Relevant required count must prevent validation"
                }
                require(controller.answerQuestion(countIndex, IntegerData(0)) ==
                    FormEntryController.ANSWER_CONSTRAINT_VIOLATED)
                require(controller.answerQuestion(countIndex, IntegerData(11)) ==
                    FormEntryController.ANSWER_CONSTRAINT_VIOLATED)
                require(controller.answerQuestion(countIndex, IntegerData(3)) ==
                    FormEntryController.ANSWER_OK)
                require(controller.getAnswer(ref(count))?.value == 3)
                require(controller.getAnswer(ref(total))?.value == 6) {
                    "Native calculation did not recompute from accepted count"
                }
                require(controller.validateAnswers(false) !is FailedValidationResult)
                setGate("no")
                require(nextQuestion() != count) { "Irrelevant count must be skipped" }
                require(controller.getAnswer(ref(total))?.value == 0)
                require(controller.validateAnswers(false) !is FailedValidationResult)
                setGate("yes")
                require(nextQuestion() == count) { "Relevant count must reappear" }
                val restoredIndex = controller.getFormIndex()
                require(controller.answerQuestion(restoredIndex, IntegerData(5)) ==
                    FormEntryController.ANSWER_OK)
                require(controller.getAnswer(ref(total))?.value == 10)
                require(controller.validateAnswers(false) !is FailedValidationResult)
                conditionalVerified = true
            }
            var mediaReceipt: ProtectedImageReceipt? = null
            var mediaVerified = false
            if (directImagePath != null) {
                require(directImagePath in imagePaths &&
                    repeatPaths.none { directImagePath.startsWith(it + "/") } &&
                    approval.decisions[directImagePath] == MappingDecision.REENTER) {
                    "Only a reviewed direct native image field can receive a protected asset"
                }
                val candidate = plan.candidates.single { it.targetPath == directImagePath }
                require(candidate.risks.none {
                    it in setOf(MappingRisk.REPEAT, MappingRisk.DYNAMIC_SELECT,
                        MappingRisk.CALCULATION, MappingRisk.RELEVANCE_OR_CONSTRAINT,
                        MappingRisk.UNBOUND_FIELD)
                })
                var found = first == directImagePath
                for (attempt in 1..100) {
                    if (found) break
                    val event = controller.stepToNextEvent(true)
                    if (event == FormEntryController.EVENT_END_OF_FORM) break
                    found = event == FormEntryController.EVENT_QUESTION &&
                        absolutePath(controller.getFormIndex().reference) == directImagePath
                }
                require(found) { "Reviewed image question must be reachable" }
                val index = controller.getFormIndex()
                val ref = index.reference
                val node = requireNotNull(def.instance.resolveReference(ref))
                require(node.isLeaf && node.dataType == Constants.DATATYPE_BINARY)
                require(controller.getAnswer(ref)?.value.let { it == null || it == "" }) {
                    "Independent visit cannot inherit an image answer"
                }
                val store = QuarantinedPrivateImageStore(
                    noBackupRoot, AndroidKeyStorePrivateDraftCipher()
                )
                mediaReceipt = store.withVerifiedImage(
                    binding, directImagePath, listOf(directImagePath)
                ) { receipt, bytes ->
                    require(receipt.bytes == bytes.size &&
                        receipt.sha256 == QuarantinedPrivateImageStore.digest(bytes))
                    // ODK's FileAnswerDelegate also supplies StringData for
                    // image answers. This is an opaque RAM-only identifier,
                    // NOT a filesystem path or a sendable attachment filename.
                    val token = "protected-" + QuarantinedWorkDraftVault.sha256(
                        binding.reservedInstanceId + "\n" + directImagePath + "\n" +
                            receipt.sha256
                    )
                    require(controller.answerQuestion(index, StringData(token)) ==
                        FormEntryController.ANSWER_OK &&
                        controller.getAnswer(ref)?.value == token)
                    require(!File(folder, token).exists())
                    require(controller.answerQuestion(index, null) ==
                        FormEntryController.ANSWER_OK &&
                        controller.getAnswer(ref)?.value.let { it == null || it == "" }) {
                        "Native binary answer could not be cleared"
                    }
                    mediaVerified = true
                    receipt
                }
                if (mediaReceipt == null) {
                    require(controller.getAnswer(ref)?.value.let { it == null || it == "" })
                    mediaVerified = true
                }
            }
            require(controller.getInstanceFile() == null && !controller.isEditing())
            return ProtectedNativeControllerEvidence(
                binding.workId, binding.targetFormVersionId,
                binding.targetXFormSha256, questionPaths, imagePaths,
                repeatPaths, first, answerVerified, next,
                repeatVerified, beforeDelete, afterDelete, conditionalVerified,
                directImagePath, mediaReceipt, mediaVerified
            )
        } finally {
            check(folder.deleteRecursively()) { "Native controller scratch cleanup failed" }
        }
    }

    companion object {
        private val EXTERNAL_SRC = Regex("""\bsrc\s*=""", RegexOption.IGNORE_CASE)
        private val UNSUPPORTED_ACTION = Regex(
            """<\s*(?:[A-Za-z_][\w.-]*:)?(?:action|setvalue|setgeopoint|trigger|submission)\b""",
            RegexOption.IGNORE_CASE
        )
    }
}
