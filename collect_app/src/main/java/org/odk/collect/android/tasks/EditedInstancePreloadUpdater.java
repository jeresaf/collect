package org.odk.collect.android.tasks;

import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.data.StringData;
import org.javarosa.core.model.instance.TreeElement;
import org.javarosa.core.services.PropertyManager;

import java.util.function.Function;

/**
 * Updates property preload values in an imported instance so edits made by a different user use the
 * current user's metadata rather than the values captured when the instance was first created.
 */
class EditedInstancePreloadUpdater {
    private static final String PROPERTY_PRELOAD_HANDLER = "property";

    private EditedInstancePreloadUpdater() {
    }

    static void updatePropertyPreloads(FormDef formDef) {
        updatePropertyPreloads(
                formDef.getMainInstance().getRoot(),
                propertyName -> PropertyManager.__().getSingularProperty(propertyName)
        );
    }

    static void updatePropertyPreloads(TreeElement node, Function<String, String> propertyValueProvider) {
        if (PROPERTY_PRELOAD_HANDLER.equals(node.getPreloadHandler()) && node.isLeaf()) {
            String propertyName = node.getPreloadParams();
            String value = propertyName != null ? propertyValueProvider.apply(propertyName) : null;
            node.setAnswer(value != null && !value.trim().isEmpty() ? new StringData(value) : null);
        }

        for (int i = 0; i < node.getNumChildren(); i++) {
            updatePropertyPreloads(node.getChildAt(i), propertyValueProvider);
        }
    }
}
