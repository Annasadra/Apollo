/*
 *  Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.app;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.apollocurrency.aplwallet.apl.core.account.LedgerEvent;
import com.apollocurrency.aplwallet.apl.core.account.model.Account;
import com.apollocurrency.aplwallet.apl.core.account.service.AccountPublicKeyService;
import com.apollocurrency.aplwallet.apl.core.account.service.AccountService;
import com.apollocurrency.aplwallet.apl.core.db.dao.ShardDao;
import com.apollocurrency.aplwallet.apl.util.Constants;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class BlockApplier {
    @Inject
    @Setter
    private Blockchain blockchain;
    @Inject
    @Setter
    private ShardDao shardDao;

    @Inject @Setter
    private AccountService accountService;

    @Inject @Setter
    private AccountPublicKeyService accountPublicKeyService;

    public void apply(Block block) {
        Account generatorAccount = accountService.addOrGetAccount(block.getGeneratorId());
        accountPublicKeyService.apply(generatorAccount, block.getGeneratorPublicKey());
        long totalBackFees = 0;
        int height = block.getHeight();
        if (height > 3) {
            long[] backFees = new long[3];
            for (Transaction transaction : block.getTransactions()) {
                long[] fees = ((TransactionImpl)transaction).getBackFees();
                for (int i = 0; i < fees.length; i++) {
                    backFees[i] += fees[i];
                }
            }
            int shardHeight = blockchain.getShardInitialBlock().getHeight();
            long[] generators = null;
            for (int i = 0; i < backFees.length; i++) {
                if (backFees[i] == 0) {
                    break;
                }
                totalBackFees += backFees[i];
                int blockHeight = block.getHeight() - i - 1;
                if (generators == null && shardHeight > blockHeight) {
                    generators = shardDao.getLastShard().getGeneratorIds();
                }
                Account previousGeneratorAccount;
                if (shardHeight > blockHeight) {
                    int index = shardHeight - blockHeight - 1;
                    previousGeneratorAccount = accountService.getAccount(generators[index]);
                } else {
                    previousGeneratorAccount = accountService.getAccount(blockchain.getBlockAtHeight(blockHeight).getGeneratorId());
                }
                log.trace("Back fees {} to forger at height {}", ((double)backFees[i])/ Constants.ONE_APL,
                        height - i - 1);
                accountService.addToBalanceAndUnconfirmedBalanceATM(previousGeneratorAccount, LedgerEvent.BLOCK_GENERATED, block.getId(), backFees[i]);
                previousGeneratorAccount.addToForgedBalanceATM(backFees[i]);
            }
        }
        if (totalBackFees != 0) {
            log.trace("Fee reduced by {} at height {}", ((double)totalBackFees)/Constants.ONE_APL, height);
        }
        accountService.addToBalanceAndUnconfirmedBalanceATM(generatorAccount, LedgerEvent.BLOCK_GENERATED, block.getId(), block.getTotalFeeATM() - totalBackFees);
        generatorAccount.addToForgedBalanceATM(block.getTotalFeeATM() - totalBackFees);
    }

}
