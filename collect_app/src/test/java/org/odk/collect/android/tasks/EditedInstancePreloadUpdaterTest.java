package org.odk.collect.android.tasks;

import org.javarosa.core.model.data.StringData;
import org.javarosa.core.model.instance.TreeElement;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class EditedInstancePreloadUpdaterTest {
    @Test
    public void updatePropertyPreloads_replacesExistingPropertyPreloadValues() {
        TreeElement root = new TreeElement("data");
        TreeElement phoneNumber = propertyPreloadNode("phonenumber", "old-phone-number");
        root.addChild(phoneNumber);

        EditedInstancePreloadUpdater.updatePropertyPreloads(root, propertyName -> "new-phone-number");

        assertThat(phoneNumber.getValue().getDisplayText(), equalTo("new-phone-number"));
    }

    @Test
    public void updatePropertyPreloads_clearsExistingPropertyPreloadValuesWhenPropertyIsBlank() {
        TreeElement root = new TreeElement("data");
        TreeElement phoneNumber = propertyPreloadNode("phonenumber", "old-phone-number");
        root.addChild(phoneNumber);

        EditedInstancePreloadUpdater.updatePropertyPreloads(root, propertyName -> "");

        assertThat(phoneNumber.getValue(), nullValue());
    }

    @Test
    public void updatePropertyPreloads_doesNotUpdateNonPropertyPreloads() {
        TreeElement root = new TreeElement("data");
        TreeElement start = new TreeElement("start");
        start.setPreloadHandler("timestamp");
        start.setPreloadParams("start");
        start.setValue(new StringData("old-start"));
        root.addChild(start);

        EditedInstancePreloadUpdater.updatePropertyPreloads(root, propertyName -> "new-value");

        assertThat(start.getValue().getDisplayText(), equalTo("old-start"));
    }

    private static TreeElement propertyPreloadNode(String propertyName, String value) {
        TreeElement node = new TreeElement(propertyName);
        node.setPreloadHandler("property");
        node.setPreloadParams(propertyName);
        node.setValue(new StringData(value));
        return node;
    }
}
