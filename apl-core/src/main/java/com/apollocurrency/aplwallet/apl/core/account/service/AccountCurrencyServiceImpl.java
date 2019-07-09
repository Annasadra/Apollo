/*
 *  Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.account.service;

import com.apollocurrency.aplwallet.apl.core.account.AccountEventType;
import com.apollocurrency.aplwallet.apl.core.account.LedgerEvent;
import com.apollocurrency.aplwallet.apl.core.account.LedgerHolding;
import com.apollocurrency.aplwallet.apl.core.account.dao.AccountCurrencyTable;
import com.apollocurrency.aplwallet.apl.core.account.model.Account;
import com.apollocurrency.aplwallet.apl.core.account.model.AccountCurrency;
import com.apollocurrency.aplwallet.apl.core.account.model.LedgerEntry;
import com.apollocurrency.aplwallet.apl.core.app.Blockchain;
import com.apollocurrency.aplwallet.apl.core.db.DbIterator;

import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

import static com.apollocurrency.aplwallet.apl.core.account.observer.events.AccountEventBinding.literal;
import static com.apollocurrency.aplwallet.apl.core.account.service.AccountService.checkBalance;

/**
 * @author andrew.zinchenko@gmail.com
 */
@Singleton
public class AccountCurrencyServiceImpl implements AccountCurrencyService {

    private Blockchain blockchain;
    private AccountCurrencyTable accountCurrencyTable;
    private AccountLedgerService accountLedgerService;
    private Event<Account> accountEvent;
    private Event<AccountCurrency> accountCurrencyEvent;

    @Inject
    public AccountCurrencyServiceImpl(Blockchain blockchain, AccountCurrencyTable accountCurrencyTable, AccountLedgerService accountLedgerService, Event<Account> accountEvent, Event<AccountCurrency> accountCurrencyEvent) {
        this.blockchain = blockchain;
        this.accountCurrencyTable = accountCurrencyTable;
        this.accountLedgerService = accountLedgerService;
        this.accountEvent = accountEvent;
        this.accountCurrencyEvent = accountCurrencyEvent;
    }

    @Override
    public void save(AccountCurrency currency) {
        checkBalance(currency.getAccountId(), currency.getUnits(), currency.getUnconfirmedUnits());
        if (currency.getUnits() > 0 || currency.getUnconfirmedUnits() > 0) {
            accountCurrencyTable.insert(currency);
        } else if (currency.getUnits() == 0 && currency.getUnconfirmedUnits() == 0) {
            accountCurrencyTable.delete(currency);
        }
    }

    @Override
    public AccountCurrency getAccountCurrency(Account account, long currencyId) {
        return getAccountCurrency(account.getId(), currencyId);
    }

    @Override
    public AccountCurrency getAccountCurrency(long accountId, long currencyId) {
        return accountCurrencyTable.get(AccountCurrencyTable.newKey(accountId, currencyId));
    }

    @Override
    public AccountCurrency getAccountCurrency(Account account, long currencyId, int height) {
        return getAccountCurrency(account.getId(), currencyId, height);
    }

    @Override
    public AccountCurrency getAccountCurrency(long accountId, long currencyId, int height) {
        return accountCurrencyTable.get(AccountCurrencyTable.newKey(accountId, currencyId), height);
    }

    @Override
    public int getCurrencyAccountCount(long currencyId) {
        return accountCurrencyTable.getCurrencyAccountCount(currencyId);
    }

    @Override
    public int getCurrencyAccountCount(long currencyId, int height) {
        return accountCurrencyTable.getCurrencyAccountCount(currencyId, height);
    }

    @Override
    public int getAccountCurrencyCount(long accountId) {
        return accountCurrencyTable.getAccountCurrencyCount(accountId);
    }

    @Override
    public int getAccountCurrencyCount(long accountId, int height) {
        return accountCurrencyTable.getAccountCurrencyCount(accountId, height);
    }

    @Override
    public List<AccountCurrency> getCurrencies(Account account, int from, int to) {
        return getCurrencies(account.getId(), from, to);
    }

    @Override
    public List<AccountCurrency> getCurrencies(long accountId, int from, int to) {
        List<AccountCurrency> accountCurrencies = new ArrayList<>();
        try (DbIterator<AccountCurrency> iterator = accountCurrencyTable.getAccountCurrencies(accountId, from, to)) {
            iterator.forEachRemaining(accountCurrencies::add);
        }
        return accountCurrencies;
    }

    @Override
    public List<AccountCurrency> getCurrencies(Account account, int height, int from, int to) {
        return getCurrencies(account.getId(), height, from, to);
    }

    @Override
    public List<AccountCurrency> getCurrencies(long accountId, int height, int from, int to) {
        List<AccountCurrency> accountCurrencies = new ArrayList<>();
        try (DbIterator<AccountCurrency> iterator = accountCurrencyTable.getAccountCurrencies(accountId, height, from, to)) {
            iterator.forEachRemaining(accountCurrencies::add);
        }
        return accountCurrencies;
    }

    @Override
    public List<AccountCurrency> getCurrencyAccounts(long currencyId, int from, int to) {
        List<AccountCurrency> accountCurrencies = new ArrayList<>();
        try (DbIterator<AccountCurrency> iterator = accountCurrencyTable.getCurrencyAccounts(currencyId, from, to)) {
            iterator.forEachRemaining(accountCurrencies::add);
        }
        return accountCurrencies;
    }

    @Override
    public List<AccountCurrency> getCurrencyAccounts(long currencyId, int height, int from, int to) {
        List<AccountCurrency> accountCurrencies = new ArrayList<>();
        try (DbIterator<AccountCurrency> iterator = accountCurrencyTable.getCurrencyAccounts(currencyId, height, from, to)) {
            iterator.forEachRemaining(accountCurrencies::add);
        }
        return accountCurrencies;
    }

    @Override
    public long getCurrencyUnits(Account account, long currencyId) {
        return getCurrencyUnits(account.getId(), currencyId);
    }

    @Override
    public long getCurrencyUnits(long accountId, long currencyId) {
        AccountCurrency accountCurrency = accountCurrencyTable.get(AccountCurrencyTable.newKey(accountId, currencyId));
        return accountCurrency == null ? 0 : accountCurrency.getUnits();
    }

    @Override
    public long getCurrencyUnits(Account account, long currencyId, int height) {
        return getCurrencyUnits(account.getId(), currencyId, height);
    }
    @Override
    public long getCurrencyUnits(long accountId, long currencyId, int height) {
        AccountCurrency accountCurrency = accountCurrencyTable.get(AccountCurrencyTable.newKey(accountId, currencyId), height);
        return accountCurrency == null ? 0 : accountCurrency.getUnits();
    }

    @Override
    public long getUnconfirmedCurrencyUnits(Account account, long currencyId) {
        return getUnconfirmedCurrencyUnits(account.getId(), currencyId);
    }

    @Override
    public long getUnconfirmedCurrencyUnits(long accountId, long currencyId) {
        AccountCurrency accountCurrency = accountCurrencyTable.get(AccountCurrencyTable.newKey(accountId, currencyId));
        return accountCurrency == null ? 0 : accountCurrency.getUnconfirmedUnits();
    }

    @Override
    public void addToCurrencyUnits(Account account, LedgerEvent event, long eventId, long currencyId, long units) {
        if (units == 0) {
            return;
        }
        AccountCurrency accountCurrency;
        accountCurrency = accountCurrencyTable.get(AccountCurrencyTable.newKey(account.getId(), currencyId));
        long currencyUnits = accountCurrency == null ? 0 : accountCurrency.getUnits();
        currencyUnits = Math.addExact(currencyUnits, units);
        if (accountCurrency == null) {
            accountCurrency = new AccountCurrency(account.getId(), currencyId, currencyUnits, 0, blockchain.getHeight());
        } else {
            accountCurrency.setUnits(currencyUnits);
        }
        save(accountCurrency);
        //accountService.listeners.notify(account, AccountEventType.CURRENCY_BALANCE);
        accountEvent.select(literal(AccountEventType.CURRENCY_BALANCE)).fire(account);
        //currencyListeners.notify(accountCurrency, AccountEventType.CURRENCY_BALANCE);
        accountCurrencyEvent.select(literal(AccountEventType.CURRENCY_BALANCE)).fire(accountCurrency);
        if (accountLedgerService.mustLogEntry(account.getId(), false)) {
            accountLedgerService.logEntry(new LedgerEntry(event, eventId, account.getId(), LedgerHolding.CURRENCY_BALANCE, currencyId,
                    units, currencyUnits, blockchain.getLastBlock()));
        }
    }

    @Override
    public void addToUnconfirmedCurrencyUnits(Account account, LedgerEvent event, long eventId, long currencyId, long units) {
        if (units == 0) {
            return;
        }
        AccountCurrency accountCurrency = accountCurrencyTable.get(AccountCurrencyTable.newKey(account.getId(), currencyId));
        long unconfirmedCurrencyUnits = accountCurrency == null ? 0 : accountCurrency.getUnconfirmedUnits();
        unconfirmedCurrencyUnits = Math.addExact(unconfirmedCurrencyUnits, units);
        if (accountCurrency == null) {
            accountCurrency = new AccountCurrency(account.getId(), currencyId, 0, unconfirmedCurrencyUnits, blockchain.getHeight());
        } else {
            accountCurrency.setUnconfirmedUnits(unconfirmedCurrencyUnits);
        }
        save(accountCurrency);
        //accountService.listeners.notify(account, AccountEventType.UNCONFIRMED_CURRENCY_BALANCE);
        accountEvent.select(literal(AccountEventType.UNCONFIRMED_CURRENCY_BALANCE)).fire(account);
        //currencyListeners.notify(accountCurrency, AccountEventType.UNCONFIRMED_CURRENCY_BALANCE);
        accountCurrencyEvent.select(literal(AccountEventType.UNCONFIRMED_CURRENCY_BALANCE)).fire(accountCurrency);
        if (accountLedgerService.mustLogEntry(account.getId(), true)) {
            accountLedgerService.logEntry(new LedgerEntry(event, eventId, account.getId(),
                    LedgerHolding.UNCONFIRMED_CURRENCY_BALANCE, currencyId,
                    units, unconfirmedCurrencyUnits, blockchain.getLastBlock()));
        }
    }

    @Override
    public void addToCurrencyAndUnconfirmedCurrencyUnits(Account account, LedgerEvent event, long eventId, long currencyId, long units) {
        if (units == 0) {
            return;
        }
        AccountCurrency accountCurrency;
        accountCurrency = accountCurrencyTable.get(AccountCurrencyTable.newKey(account.getId(), currencyId));
        long currencyUnits = accountCurrency == null ? 0 : accountCurrency.getUnits();
        currencyUnits = Math.addExact(currencyUnits, units);
        long unconfirmedCurrencyUnits = accountCurrency == null ? 0 : accountCurrency.getUnconfirmedUnits();
        unconfirmedCurrencyUnits = Math.addExact(unconfirmedCurrencyUnits, units);
        if (accountCurrency == null) {
            accountCurrency = new AccountCurrency(account.getId(), currencyId, currencyUnits, unconfirmedCurrencyUnits, blockchain.getHeight());
        } else {
            accountCurrency.setUnits(currencyUnits);
            accountCurrency.setUnconfirmedUnits(unconfirmedCurrencyUnits);
        }
        save(accountCurrency);
        //accountService.listeners.notify(account, AccountEventType.CURRENCY_BALANCE);
        accountEvent.select(literal(AccountEventType.CURRENCY_BALANCE)).fire(account);
        //accountService.listeners.notify(account, AccountEventType.UNCONFIRMED_CURRENCY_BALANCE);
        accountEvent.select(literal(AccountEventType.UNCONFIRMED_CURRENCY_BALANCE)).fire(account);
        //currencyListeners.notify(accountCurrency, AccountEventType.CURRENCY_BALANCE);
        accountCurrencyEvent.select(literal(AccountEventType.CURRENCY_BALANCE)).fire(accountCurrency);
        //currencyListeners.notify(accountCurrency, AccountEventType.UNCONFIRMED_CURRENCY_BALANCE);
        accountCurrencyEvent.select(literal(AccountEventType.UNCONFIRMED_CURRENCY_BALANCE)).fire(accountCurrency);
        if (accountLedgerService.mustLogEntry(account.getId(), true)) {
            accountLedgerService.logEntry(new LedgerEntry(event, eventId, account.getId(),
                    LedgerHolding.UNCONFIRMED_CURRENCY_BALANCE, currencyId,
                    units, unconfirmedCurrencyUnits, blockchain.getLastBlock()));
        }
        if (accountLedgerService.mustLogEntry(account.getId(), false)) {
            accountLedgerService.logEntry(new LedgerEntry(event, eventId, account.getId(),
                    LedgerHolding.CURRENCY_BALANCE, currencyId,
                    units, currencyUnits, blockchain.getLastBlock()));
        }
    }
}
