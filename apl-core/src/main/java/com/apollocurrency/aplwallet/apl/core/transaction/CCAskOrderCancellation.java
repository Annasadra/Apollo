/*
 * Copyright © 2018-2019 Apollo Foundation
 */
package com.apollocurrency.aplwallet.apl.core.transaction;

import com.apollocurrency.aplwallet.apl.core.account.LedgerEvent;
import com.apollocurrency.aplwallet.apl.core.account.model.AccountEntity;
import com.apollocurrency.aplwallet.apl.core.app.Order;
import com.apollocurrency.aplwallet.apl.core.app.Transaction;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.ColoredCoinsAskOrderCancellation;
import com.apollocurrency.aplwallet.apl.util.AplException;
import org.json.simple.JSONObject;

import java.nio.ByteBuffer;

/**
 *
 * @author al
 */
class CCAskOrderCancellation extends ColoredCoinsOrderCancellation {

    public CCAskOrderCancellation() {
    }

    @Override
    public final byte getSubtype() {
        return TransactionType.SUBTYPE_COLORED_COINS_ASK_ORDER_CANCELLATION;
    }

    @Override
    public LedgerEvent getLedgerEvent() {
        return LedgerEvent.ASSET_ASK_ORDER_CANCELLATION;
    }

    @Override
    public String getName() {
        return "AskOrderCancellation";
    }

    @Override
    public ColoredCoinsAskOrderCancellation parseAttachment(ByteBuffer buffer) throws AplException.NotValidException {
        return new ColoredCoinsAskOrderCancellation(buffer);
    }

    @Override
    public ColoredCoinsAskOrderCancellation parseAttachment(JSONObject attachmentData) throws AplException.NotValidException {
        return new ColoredCoinsAskOrderCancellation(attachmentData);
    }

    @Override
    public void applyAttachment(Transaction transaction, AccountEntity senderAccount, AccountEntity recipientAccount) {
        ColoredCoinsAskOrderCancellation attachment = (ColoredCoinsAskOrderCancellation) transaction.getAttachment();
        Order order = Order.Ask.getAskOrder(attachment.getOrderId());
        Order.Ask.removeOrder(attachment.getOrderId());
        if (order != null) {
            lookupAccountAssetService().addToUnconfirmedAssetBalanceATU(senderAccount, getLedgerEvent(), transaction.getId(), order.getAssetId(), order.getQuantityATU());
        }
    }

    @Override
    public void validateAttachment(Transaction transaction) throws AplException.ValidationException {
        ColoredCoinsAskOrderCancellation attachment = (ColoredCoinsAskOrderCancellation) transaction.getAttachment();
        Order ask = Order.Ask.getAskOrder(attachment.getOrderId());
        if (ask == null) {
            throw new AplException.NotCurrentlyValidException("Invalid ask order: " + Long.toUnsignedString(attachment.getOrderId()));
        }
        if (ask.getAccountId() != transaction.getSenderId()) {
            throw new AplException.NotValidException("Order " + Long.toUnsignedString(attachment.getOrderId()) + " was created by account " + Long.toUnsignedString(ask.getAccountId()));
        }
    }
    
}
