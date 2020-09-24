/*
 * Copyright (c)  2018-2020. Apollo Foundation.
 */
package com.apollocurrency.aplwallet.apl.security.id;

import io.firstbridge.identity.handler.IdValidator;
import io.firstbridge.identity.handler.ThisActorIdHandler;


/**
 * Service that handles identity of peers using X.509 certificates od nodes
 * @author alukin@gmail.com
 */
public interface IdentityService {

    ThisActorIdHandler getThisNodeIdHandler();
    IdValidator getPeerIdValidator();
    /**
     * Load all available certificates and keys from defined directories/resources
     */
    void loadAll();
}
