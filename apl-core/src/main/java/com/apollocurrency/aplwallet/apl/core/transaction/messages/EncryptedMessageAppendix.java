/*
 * Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.transaction.messages;

import com.apollocurrency.aplwallet.apl.core.entity.blockchain.Transaction;
import com.apollocurrency.aplwallet.apl.crypto.EncryptedData;
import com.apollocurrency.aplwallet.apl.core.app.AplException;
import org.json.simple.JSONObject;

import java.nio.ByteBuffer;

public class EncryptedMessageAppendix extends AbstractEncryptedMessageAppendix {

    private static final String appendixName = "EncryptedMessage";

    public EncryptedMessageAppendix(ByteBuffer buffer) throws AplException.NotValidException {
        super(buffer);
    }

    public EncryptedMessageAppendix(JSONObject attachmentData) {
        super(attachmentData, (JSONObject) attachmentData.get("encryptedMessage"));
    }

    public EncryptedMessageAppendix(EncryptedData encryptedData, boolean isText, boolean isCompressed) {
        super(encryptedData, isText, isCompressed);
    }

    public static EncryptedMessageAppendix parse(JSONObject attachmentData) {
        if (!Appendix.hasAppendix(appendixName, attachmentData)) {
            return null;
        }
        if (((JSONObject) attachmentData.get("encryptedMessage")).get("data") == null) {
            return new UnencryptedEncryptedMessageAppendix(attachmentData);
        }
        return new EncryptedMessageAppendix(attachmentData);
    }

    @Override
    public String getAppendixName() {
        return appendixName;
    }

    @Override
    public void putMyJSON(JSONObject json) {
        JSONObject encryptedMessageJSON = new JSONObject();
        super.putMyJSON(encryptedMessageJSON);
        json.put("encryptedMessage", encryptedMessageJSON);
    }

    @Override
    public void validate(Transaction transaction, int blockHeight) throws AplException.ValidationException {
        super.validate(transaction, blockHeight);
        if (transaction.getRecipientId() == 0) {
            throw new AplException.NotValidException("Encrypted messages cannot be attached to transactions with no recipient");
        }
    }

}