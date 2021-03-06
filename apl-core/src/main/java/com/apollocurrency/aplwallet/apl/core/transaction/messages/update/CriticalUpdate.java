/*
 * Copyright © 2018-2019 Apollo Foundation
 */
package com.apollocurrency.aplwallet.apl.core.transaction.messages.update;

import com.apollocurrency.aplwallet.apl.core.transaction.TransactionType;
import com.apollocurrency.aplwallet.apl.core.transaction.Update;
import com.apollocurrency.aplwallet.apl.util.AplException;
import com.apollocurrency.aplwallet.apl.util.DoubleByteArrayTuple;
import com.apollocurrency.aplwallet.apl.util.Version;
import com.apollocurrency.aplwallet.apl.util.env.Architecture;
import com.apollocurrency.aplwallet.apl.util.env.Platform;
import org.json.simple.JSONObject;

import java.nio.ByteBuffer;

/**
 * @author al
 */
public final class CriticalUpdate extends UpdateAttachment {

    public CriticalUpdate(ByteBuffer buffer) throws AplException.NotValidException {
        super(buffer);
    }

    public CriticalUpdate(JSONObject attachmentData) {
        super(attachmentData);
    }

    public CriticalUpdate(Platform platform, Architecture architecture, DoubleByteArrayTuple url, Version version, byte[] hash) {
        super(platform, architecture, url, version, hash);
    }

    @Override
    public TransactionType getTransactionType() {
        return Update.CRITICAL;
    }

}
