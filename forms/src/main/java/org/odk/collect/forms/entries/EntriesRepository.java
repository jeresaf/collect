package org.odk.collect.forms.entries;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.odk.collect.forms.Form;
import org.odk.collect.forms.instances.Instance;

import java.util.List;

public interface EntriesRepository {

    @Nullable
    Entry get(Long id);

    @Nullable
    Entry getOneByPath(String path);

    @Nullable
    Entry getOneByInstanceId(String instanceId);

    @Nullable
    Entry getOneByMd5Hash(@NotNull String hash);

    List<Entry> getAll();

    List<Entry> getAllByInstanceId(String instanceId);

    List<Entry> getAllByStatus(String... status);

    int getCountByStatus(String... status);

    Entry save(@NotNull Entry entry);

    List<Entry> getAllNotDeletedByFormIdAndVersion(String formId, String version);

    void delete(Long id);

    void deleteByMd5Hash(@NotNull String md5Hash);

    void deleteByInstanceId(@NotNull String instanceId);

    void deleteAll();

    void deleteWithLogging(Long id);
}
