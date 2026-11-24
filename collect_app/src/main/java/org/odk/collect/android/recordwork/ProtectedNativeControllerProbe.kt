package org.odk.collect.android.recordwork

import org.javarosa.core.model.Constants
import org.javarosa.core.model.FormDef
import org.javarosa.core.model.GroupDef
import org.javarosa.core.model.IFormElement
import org.javarosa.core.model.QuestionDef
import org.javarosa.core.model.data.StringData
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
        firstTextAnswer: String? = null
    ): ProtectedNativeControllerEvidence {
        // This is a disposable controller exercise, not a draft save API.
        require(firstTextAnswer == null ||
            (firstTextAnswer.isNotBlank() && firstTextAnswer.length <= 16_384 &&
                '\u0000' !in firstTextAnswer)) { "Invalid temporary native text answer" }
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
            require(controller.getInstanceFile() == null && !controller.isEditing())
            return ProtectedNativeControllerEvidence(
                binding.workId, binding.targetFormVersionId,
                binding.targetXFormSha256, questionPaths, imagePaths,
                repeatPaths, first, answerVerified, next
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
