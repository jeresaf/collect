package org.odk.collect.android.entrymanagement;

import static org.odk.collect.android.utilities.FileUtils.interuptablyWriteFile;

import org.jetbrains.annotations.NotNull;
import org.odk.collect.android.utilities.EntryNameUtils;
import org.odk.collect.android.utilities.FileUtils;
import org.odk.collect.forms.entries.EntriesRepository;
import org.odk.collect.forms.entries.Entry;
import org.odk.collect.forms.entries.EntrySource;
import org.odk.collect.forms.entries.EntrySourceException;
import org.odk.collect.shared.files.DirectoryUtils;
import org.odk.collect.shared.strings.Md5;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import timber.log.Timber;

public class ServerEntryDownloader implements EntryDownloader {

    private final EntriesRepository entriesRepository;
    private final EntrySource entrySource;
    private final File cacheDir;
    private final String entriesDirPath;
    private final Supplier<Long> clock;

    public ServerEntryDownloader(EntrySource entrySource, EntriesRepository entriesRepository, File cacheDir, String entriesDirPath, Supplier<Long> clock) {
        this.entrySource = entrySource;
        this.cacheDir = cacheDir;
        this.entriesDirPath = entriesDirPath;
        this.entriesRepository = entriesRepository;
        this.clock = clock;
    }

    @Override
    public void downloadEntry(ServerEntryDetails entry, @Nullable Supplier<Boolean> isCancelled) throws EntryDownloadException {
        Entry entryOnDevice;
        List<Entry> preExistingEntriesWithSameInstanceId = new ArrayList<>();
        try {
            entryOnDevice = entriesRepository.getOneByMd5Hash(validateHash(entry.getHash()));
        } catch (IllegalArgumentException e) {
            throw new EntryDownloadException.EntryWithNoHash();
        }

        if (entryOnDevice != null) {
            if (entryOnDevice.isDeleted()) {
                entriesRepository.delete(entryOnDevice.getDbId());
            }
        } else {
            preExistingEntriesWithSameInstanceId = entriesRepository.getAllByInstanceId(entry.getInstanceId());
        }

        File tempDir = new File(cacheDir, "download-" + UUID.randomUUID().toString());
        tempDir.mkdirs();

        try {
            processOneEntry(entry, tempDir, entriesDirPath);
        } catch (EntrySourceException e) {
            throw new EntryDownloadException.EntrySourceError(e);
        } finally {
            DirectoryUtils.deleteDirectory(tempDir);
            for (Entry entryToDelete : preExistingEntriesWithSameInstanceId) {
                entriesRepository.delete(entryToDelete.getDbId());
            }
        }
    }

    private void processOneEntry(ServerEntryDetails fd, File tempDir, String entriesDirPath) throws EntryDownloadException, EntrySourceException {
        FileResult fileResult = null;

        try {
            // get the xml file
            // if we've downloaded a duplicate, this gives us the file
            fileResult = downloadXform(fd.getDisplayName(), fd.getDownloadUrl(), tempDir, entriesDirPath);

        } catch (EntryDownloadException.DownloadingInterrupted | InterruptedException e) {
            Timber.i(e);
            cleanUp(fileResult);
            throw new EntryDownloadException.DownloadingInterrupted();
        } catch (IOException e) {
            throw new EntryDownloadException.DiskError();
        }

        try {
            installEverything(fileResult, fd, entriesDirPath);
        } catch (EntryDownloadException.DiskError e) {
            cleanUp(fileResult);
            throw e;
        }
    }

    private void installEverything(FileResult fileResult, ServerEntryDetails fd, String entriesDirPath) throws EntryDownloadException.DiskError {

        File entryFile;

        if (fileResult.isNew()) {
            // Copy entry to entries dir
            entryFile = new File(entriesDirPath, fileResult.file.getName());
            FileUtils.copyFile(fileResult.file, entryFile);
        } else {
            entryFile = fileResult.file;
        }

        // Save entry in database
        findOrCreateEntry(entryFile, fd);

    }

    private void cleanUp(FileResult fileResult) {
        if (fileResult == null) {
            Timber.d("The user cancelled (or an exception happened) the download of a entry at the very beginning.");
        } else {
            String md5Hash = Md5.getMd5Hash(fileResult.file);
            if (md5Hash != null) {
                entriesRepository.deleteByMd5Hash(md5Hash);
            }
            FileUtils.deleteAndReport(fileResult.getFile());
        }
    }

    private EntryResult findOrCreateEntry(File entryFile, ServerEntryDetails fd) {

        Entry existingEntry = entriesRepository.getOneByPath(entryFile.getAbsolutePath());

        if (existingEntry == null) {
            Entry newEntry = saveNewEntry(fd, entryFile);
            return new EntryResult(newEntry, true);
        } else {
            return new EntryResult(existingEntry, false);
        }
    }

    private Entry saveNewEntry(ServerEntryDetails fd, File entryFile) {
        Entry entry = new Entry.Builder()
                .entryFilePath(entryFile.getAbsolutePath())
                .date(fd.getDate())
                .start(fd.getStart())
                .end(fd.getEnd())
                .formType(fd.getFormType())
                .issue(fd.getIssue())
                .deviceId(fd.getDeviceId())
                .instanceId(fd.getInstanceId())
                .status(Entry.STATUS_INCOMPLETE)
                .md5Hash(fd.getHash())
                .displayName(fd.getDisplayName())
                .formVersion(fd.getVersion())
                .formId(fd.getFormId())
                .build();

        return entriesRepository.save(entry);
    }

    /**
     * Takes the formName and the URL and attempts to download the specified file. Returns a file
     * object representing the downloaded file.
     */
    private FileResult downloadXform(String entryName, String url, File tempDir, String entriesDirPath) throws EntrySourceException, IOException, EntryDownloadException.DownloadingInterrupted, InterruptedException {
        Timber.e("downloadXform Url for xml: %s", url);

        InputStream xform = entrySource.fetchEntry(url);

        String fileName = getEntryFileName(entryName, entriesDirPath);
        File tempEntryFile = new File(tempDir + File.separator + fileName);
        interuptablyWriteFile(xform, tempEntryFile, tempDir, null);

        // we've downloaded the file, and we may have renamed it
        // make sure it's not the same as a file we already have
        Entry entry = entriesRepository.getOneByMd5Hash(Md5.getMd5Hash(tempEntryFile));
        if (entry != null) {
            // delete the file we just downloaded, because it's a duplicate
            FileUtils.deleteAndReport(tempEntryFile);

            // set the file returned to the file we already had
            return new FileResult(new File(entry.getEntryFilePath()), false);
        } else {
            return new FileResult(tempEntryFile, true);
        }
    }

    @NotNull
    private static String getEntryFileName(String entryName, String entriesDirPath) {
        String formattedEntryName = EntryNameUtils.formatFilenameFromEntryName(entryName);
        String fileName = formattedEntryName + ".xml";
        int i = 2;
        while (new File(entriesDirPath + File.separator + fileName).exists()) {
            fileName = formattedEntryName + "_" + i + ".xml";
            i++;
        }
        return fileName;
    }

    private static String validateHash(String hash) {
        return hash == null || hash.isEmpty() ? null : hash;
    }

    private static class EntryResult {

        private final Entry entry;
        private final boolean isNew;

        private EntryResult(Entry entry, boolean isNew) {
            this.entry = entry;
            this.isNew = isNew;
        }

        private boolean isNew() {
            return isNew;
        }

        public Entry getEntry() {
            return entry;
        }
    }

    private static class FileResult {

        private final File file;
        private final boolean isNew;

        FileResult(File file, boolean isNew) {
            this.file = file;
            this.isNew = isNew;
        }

        private File getFile() {
            return file;
        }

        private boolean isNew() {
            return isNew;
        }
    }
}
