package org.odk.collect.android.formentrytracker;

import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.FormIndex;
import org.javarosa.form.api.FormEntryCaption;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.form.api.FormEntryModel;

import timber.log.Timber;

public class FormEntryTracker {

    private final FormEntryController formEntryControllerToBeValidated;

    public FormEntryTracker(FormDef formDef) {
        FormEntryModel formEntryModelToBeValidated = new FormEntryModel(formDef);
        formEntryControllerToBeValidated = new FormEntryController(formEntryModelToBeValidated);
    }

    public boolean getProgressBarVisibility(FormIndex formIndex) {
        formEntryControllerToBeValidated.jumpToIndex(formIndex);
        FormEntryCaption[] groups = formEntryControllerToBeValidated.getModel().getCaptionHierarchy();
        if (groups == null || groups.length == 0) {
            return false;
        } else {
            for (FormEntryCaption group : groups) {
                if (group.getLongText().equals("Identification") || group.getLongText().equals("Verbal Consent")) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Invoked to track a filled-in form percentage. Sweeps through from beginning to
     * end, confirming that the entered values satisfy all constraints. The
     * FormEntryController is based upon the FormDef, but has its own model and
     * controller independent of anything at the UI layer.
     * From TriggerableDag.java
     */
    public int[] getCompletionPercentage() {

        formEntryControllerToBeValidated.jumpToIndex(FormIndex.createBeginningOfFormIndex());

        int questions = 0;
        int answers = 0;
        int progress = -1;

        int event;
        while ((event =
                formEntryControllerToBeValidated.stepToNextEvent()) != FormEntryController.EVENT_END_OF_FORM) {
            if (event == FormEntryController.EVENT_QUESTION) {
                questions++;
                FormIndex formControllerToBeValidatedFormIndex = formEntryControllerToBeValidated.getModel().getFormIndex();

                int saveStatus = formEntryControllerToBeValidated.answerQuestion(
                        formControllerToBeValidatedFormIndex,
                        formEntryControllerToBeValidated.getModel().getQuestionPrompt().getAnswerValue(),
                        false
                );
                if (saveStatus == FormEntryController.ANSWER_OK) {
                    answers++;
                }
            }
        }
        progress = questions > 0 ? ((answers * 100) / questions) : 0;
        Timber.e("Questions: %s, Answers: %s, Progress: %s, Calculation: %d", questions, answers, progress, (int) ((answers * 100) / questions));
        return new int[]{questions, answers, progress};
    }

}
