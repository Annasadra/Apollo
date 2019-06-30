/*
 * Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.shard.helper;

import java.nio.file.Path;

/**
 * Component's interface for importing table data into Db from CSV file.
 * Configuration parameters are supplied in implementation Constructor.
 *
 * @author yuriy.larin
 */
public interface CsvImporter {

    /**
     * Return path to data export/import folder
     *
     * @return path to folder
     */
    Path getDataExportPath();

    /**
     * Import one specified table and returns number of imported rows
     * The CSV file should exist in folder specified by implementation component.
     * If file was not found it's skipped from processing
     *
     * @param tableName table to import from csv into database
     * @param batchLimit rows in batch to process
     * @param cleanTarget true if we want to cleanup target table
     * @return imported quantity
     */
    long importCsv(String tableName, int batchLimit, boolean cleanTarget) throws Exception;

    /**
     * Import one specified table and returns number of imported rows
     * The CSV file should exist in folder specified by implementation component.
     * If file was not found it's skipped from processing
     *
     * @param tableName table to import from csv into database
     * @param batchLimit rows in batch to process
     * @param cleanTarget true if we want to cleanup target table
     * @param stateIncrease progress increase value
     * @return imported quantity
     */
    long importCsv(String tableName, int batchLimit, boolean cleanTarget, Double stateIncrease) throws Exception;

}
