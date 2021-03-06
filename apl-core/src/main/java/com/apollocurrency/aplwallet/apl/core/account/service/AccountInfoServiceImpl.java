/*
 *  Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.account.service;

import com.apollocurrency.aplwallet.apl.core.account.dao.AccountInfoTable;
import com.apollocurrency.aplwallet.apl.core.account.dao.AccountTable;
import com.apollocurrency.aplwallet.apl.core.account.model.Account;
import com.apollocurrency.aplwallet.apl.core.account.model.AccountInfo;
import com.apollocurrency.aplwallet.apl.core.app.Blockchain;
import com.apollocurrency.aplwallet.apl.crypto.Convert;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

import static com.apollocurrency.aplwallet.apl.core.app.CollectionUtil.toList;

/**
 * @author andrew.zinchenko@gmail.com
 */
@Singleton
public class AccountInfoServiceImpl implements AccountInfoService {

    private final Blockchain blockchain;
    private final AccountInfoTable accountInfoTable;

    @Inject
    public AccountInfoServiceImpl(Blockchain blockchain, AccountInfoTable accountInfoTable) {
        this.blockchain = blockchain;
        this.accountInfoTable = accountInfoTable;
    }

    @Override
    public void update(AccountInfo accountInfo) {
        if (accountInfo.getName() != null || accountInfo.getDescription() != null) {
            accountInfoTable.insert(accountInfo);
        } else {
            accountInfoTable.deleteAtHeight(accountInfo, blockchain.getHeight());
        }
    }

    @Override
    public AccountInfo getAccountInfo(Account account) {
        return accountInfoTable.get(AccountTable.newKey(account));
    }

    @Override
    public void updateAccountInfo(Account account, String name, String description) {
        name = Convert.emptyToNull(name.trim());
        description = Convert.emptyToNull(description.trim());
        AccountInfo accountInfo = getAccountInfo(account);
        if (accountInfo == null) {
            accountInfo = new AccountInfo(account.getId(), name, description, blockchain.getHeight());
        } else {
            accountInfo.setName(name);
            accountInfo.setDescription(description);
            accountInfo.setHeight(blockchain.getHeight());
        }
        update(accountInfo);
    }

    @Override
    public List<AccountInfo> searchAccounts(String query, int from, int to) {
        return toList(accountInfoTable.searchAccounts(query, from, to));
    }

}
