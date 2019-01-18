/*
 * Copyright © 2018 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.db.migrator;

import java.nio.file.Path;

public interface DbInfoExtractor {

    int getHeight(String dbDir);

    Path getPath(String dbDir);
}
