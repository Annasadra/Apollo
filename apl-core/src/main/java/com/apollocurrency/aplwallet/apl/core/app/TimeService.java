/*
 * Copyright © 2018 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.app;

public interface TimeService {
    int getEpochTime();

    /**
     * @return seconds since unix epoch
     */
    long systemTime();
}
