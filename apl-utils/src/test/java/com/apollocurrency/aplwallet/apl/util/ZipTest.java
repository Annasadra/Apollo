package com.apollocurrency.aplwallet.apl.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ZipTest {

    @RegisterExtension
    static TemporaryFolderExtension temporaryFolderExtension = new TemporaryFolderExtension();

    private Zip zipComponent;
    private Path targetPath;
    private Set<String> tables = Set.of("account_control_phasing", "phasing_poll", "public_key", "purchase", "shard", "shuffling_data");

    @BeforeEach
    void setUp() {
        ResourceFileLoader resourceFileLoader = new ResourceFileLoader();
        targetPath = resourceFileLoader.getResourcePath();
        assertNotNull(targetPath);
        zipComponent = new Zip();
    }

    @Test
    void extract() {

    }

    @Test
//    @Disabled
    void compress() {
        Instant backTo1970 = Instant.EPOCH; // in past
        long filesTimeFromEpoch = backTo1970.toEpochMilli();
        // create ZIP in temp folder for unit test
        String fileInPath = temporaryFolderExtension.getRoot().toPath().toFile() + "test-archive-csv-1.zip";
        // start creating zip for all CSV
        boolean isCompressed = zipComponent.compress(fileInPath, targetPath.toAbsolutePath().toString(),
                filesTimeFromEpoch, new SuffixFileFilter(".csv"));
        assertTrue(isCompressed);

        String[] extensions = new String[]{"zip"};
        Collection filesInFolder = FileUtils.listFiles(targetPath.toFile(), extensions, false);
        assertNotNull(filesInFolder);
//        assertEquals(1, filesInFolder.size());
//        ((File) filesInFolder.iterator().next()).getName().equalsIgnoreCase(tableName + CsvAbstractBase.CSV_FILE_EXTENSION);
    }

    @Test
    void incorrectParamsCall() {
        assertThrows(NullPointerException.class, () -> zipComponent.compress(
                null, "", -1L, null));

        assertThrows(NullPointerException.class, () -> zipComponent.compress(
                "", null, -1L, null));

        assertThrows(IllegalArgumentException.class, () -> zipComponent.compress(
                "", targetPath.toAbsolutePath().toString(), -1L, null));

        assertThrows(IllegalArgumentException.class, () -> zipComponent.compress(
                targetPath.toAbsolutePath().toString(), "", -1L, null));

        assertThrows(NullPointerException.class, () -> zipComponent.extract(null, ""));

        assertThrows(NullPointerException.class, () -> zipComponent.extract("", null));

        assertThrows(IllegalArgumentException.class, () -> zipComponent.extract("", targetPath.toAbsolutePath().toString()));

        assertThrows(IllegalArgumentException.class, () -> zipComponent.extract(targetPath.toAbsolutePath().toString(), ""));

    }
}