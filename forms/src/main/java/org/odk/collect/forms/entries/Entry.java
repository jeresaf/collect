/*
 * Copyright 2017 Nafundi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.odk.collect.forms.entries;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.odk.collect.forms.Form;
import org.odk.collect.forms.instances.Instance;

/**
 * A form definition stored on the device.
 * <p>
 * Objects of this class are created using the builder pattern: https://en.wikipedia.org/wiki/Builder_pattern
 */
public final class Entry {

    public static final String STATUS_INCOMPLETE = "incomplete";
    public static final String STATUS_COMPLETE = "complete";
    public static final String STATUS_SUBMITTED = "submitted";
    public static final String STATUS_SUBMISSION_FAILED = "submissionFailed";

    private final String displayName;
    private final String formType;
    private final String issue;
    private final String start;
    private final String end;
    private final String date;
    private final String deviceId;
    private final String instanceId;
    private final String entryFilePath;
    private final String md5Hash;
    private final String formId;
    private final String formVersion;
    private final String status;
    private final Long deletedDate;
    private final boolean deleted;

    private final Long dbId;

    private Entry(Entry.Builder builder) {
        displayName = builder.displayName;
        formType = builder.formType;
        issue = builder.issue;
        start = builder.start;
        end = builder.end;
        date = builder.date;
        deviceId = builder.deviceId;
        instanceId = builder.instanceId;
        entryFilePath = builder.entryFilePath;
        md5Hash = builder.md5Hash;
        formId = builder.formId;
        formVersion = builder.formVersion;
        status = builder.status;
        deletedDate = builder.deletedDate;
        deleted = builder.deleted;

        dbId = builder.dbId;
    }

    public static class Builder {
        private String displayName;
        private String formType;
        private String issue;
        private String start;
        private String end;
        private String date;
        private String deviceId;
        private String instanceId;
        private String entryFilePath;
        private String md5Hash;
        private String formId;
        private String formVersion;
        private String status;
        private Long deletedDate;
        private boolean deleted;

        private Long dbId;

        public Builder() {

        }

        public Builder(Entry entry) {
            dbId = entry.dbId;
            displayName = entry.displayName;
            formType = entry.formType;
            issue = entry.issue;
            start = entry.start;
            end = entry.end;
            date = entry.date;
            deviceId = entry.deviceId;
            instanceId = entry.instanceId;
            entryFilePath = entry.entryFilePath;
            md5Hash = entry.md5Hash;
            formId = entry.formId;
            formVersion = entry.formVersion;
            status = entry.status;
            deletedDate = entry.deletedDate;
            deleted = entry.deleted;
        }

        public Entry.Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Entry.Builder formType(String formType) {
            this.formType = formType;
            return this;
        }

        public Entry.Builder issue(String issue) {
            this.issue = issue;
            return this;
        }

        public Entry.Builder start(String start) {
            this.start = start;
            return this;
        }

        public Entry.Builder end(String end) {
            this.end = end;
            return this;
        }

        public Entry.Builder date(String date) {
            this.date = date;
            return this;
        }

        public Entry.Builder deviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public Entry.Builder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public Entry.Builder entryFilePath(String entryFilePath) {
            this.entryFilePath = entryFilePath;
            return this;
        }

        public Entry.Builder md5Hash(String md5Hash) {
            this.md5Hash = md5Hash;
            return this;
        }

        public Entry.Builder formId(String formId) {
            this.formId = formId;
            return this;
        }

        public Entry.Builder formVersion(String jrVersion) {
            this.formVersion = jrVersion;
            return this;
        }

        public Entry.Builder status(String status) {
            this.status = status;
            return this;
        }

        public Entry.Builder deletedDate(Long deletedDate) {
            this.deletedDate = deletedDate;
            return this;
        }

        public Entry.Builder deleted(boolean deleted) {
            this.deleted = deleted;
            return this;
        }

        public Entry.Builder dbId(Long dbId) {
            this.dbId = dbId;
            return this;
        }

        public Entry build() {
            return new Entry(this);
        }
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFormType() {
        return formType;
    }

    public String getIssue() {
        return issue;
    }

    public String getStart() {
        return start;
    }

    public String getEnd() {
        return end;
    }

    public String getDate() {
        return date;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getEntryFilePath() {
        return entryFilePath;
    }

    public String getFormId() {
        return formId;
    }

    public String getMD5Hash() {
        return md5Hash;
    }

    public String getFormVersion() {
        return formVersion;
    }

    public String getStatus() {
        return status;
    }

    public Long getDeletedDate() {
        return deletedDate;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public Long getDbId() {
        return dbId;
    }

    @Override
    public boolean equals(Object other) {
        return other == this || other instanceof Entry
                && this.md5Hash.equals(((Entry) other).md5Hash);
    }

    @Override
    public int hashCode() {
        return md5Hash.hashCode();
    }
}
