/*
 * Copyright © 2018-2020 Apollo Foundation
 */
package com.apollocurrency.aplwallet.apl.core.model;

import com.apollocurrency.aplwallet.apl.core.account.model.Account;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.Appendix;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.Attachment;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.EncryptToSelfMessageAppendix;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.PhasingAppendix;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateTransactionRequest {

    private String deadlineValue;
    private String referencedTransactionFullHash;
    private String secretPhrase;
    private String publicKeyValue;
    private String passphrase;
    private boolean broadcast;
    private boolean validate = true;
    private int timestamp;


    private boolean encryptedMessageIsPrunable;
    private boolean messageIsPrunable;
    /**
     * EncryptedMessageAppendix / PrunableEncryptedMessageAppendix
     */
    private Appendix appendix;
    /**
     * EncryptToSelfMessageAppendix / MessageAppendix
     */
    private Appendix message;
    private EncryptToSelfMessageAppendix encryptToSelfMessage;
    private boolean phased;
    private PhasingAppendix phasing;

    private Account senderAccount;
    private long recipientId;
    private String recipientPublicKey;

    private long feeATM;
    private long amountATM;
    private int ecBlockHeight;
    private long ecBlockId;
    private byte[] publicKey;
    private byte[] keySeed;

    private Attachment attachment;


}
