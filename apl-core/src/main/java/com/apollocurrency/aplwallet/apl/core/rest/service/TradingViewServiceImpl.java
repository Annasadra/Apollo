/*
 * Copyright © 2018-2019 Apollo Foundation
 */
package com.apollocurrency.aplwallet.apl.core.rest.service;

import com.apollocurrency.aplwallet.apl.exchange.dao.DexTradeDao;
import com.apollocurrency.aplwallet.apl.exchange.model.DexTradeEntry;
import com.apollocurrency.aplwallet.apl.exchange.service.DexService;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Serhiy Lymar
 */

@Singleton
public class TradingViewServiceImpl implements TradingViewService{
    
    private static final Logger log = LoggerFactory.getLogger(TransportInteractionServiceImpl.class);

    @Setter
    private volatile boolean done;    
    private DexService dexService;
                                                 
    private static final long initialTimestamp = 1569888000000L; // GMT: Tuesday, 1 October 2019 г., 0:00:00   
    private static final long endTimestemp =  1574208000000L; // Date and time (GMT): Wednesday, 20 November 2019 г., 0:00:00
    private static final long discreteInterval = 60; // sec for histominute
    private static final long testUpdateInterval = 10; // sec between closed orders
        
    @Inject
    private DexTradeDao dao;
           
    @Inject
    TradingViewServiceImpl( DexService  dexService)  {
        this.dexService =  Objects.requireNonNull( dexService, "dexService is null");
    }
    
    
    void createTestEntry() {
        
        long currentTime = System.currentTimeMillis() + 86400000L;
        Integer height = 45123; 
        Random random = new Random();
      //  long v = ThreadLocalRandom.current().nextLong(100);
        
        for ( Long i = initialTimestamp; i<= currentTime; i+= (testUpdateInterval * 1000) ) {
            DexTradeEntry dexTradeEntryWrite = new DexTradeEntry(null, null);
            dexTradeEntryWrite.setSenderOfferID(random.nextLong());        
            dexTradeEntryWrite.setReceiverOfferID(random.nextLong());
            dexTradeEntryWrite.setSenderOfferType((byte)0);        
            dexTradeEntryWrite.setSenderOfferCurrency((byte)0);        
            dexTradeEntryWrite.setSenderOfferAmount( ThreadLocalRandom.current().nextLong(10000) );
            dexTradeEntryWrite.setPairCurrency((byte)1);
            dexTradeEntryWrite.setPairRate(BigDecimal.valueOf(random.nextInt(1000))); 
            //log.debug("pair rate: {}" , dexTradeEntryWrite.getPairRate());
            Long randomTransactionID = random.nextLong();
            dexTradeEntryWrite.setTransactionID(randomTransactionID);
            dexTradeEntryWrite.setFinishTime(i);            
            dexTradeEntryWrite.setHeight(height);
            height++;                        
            dao.saveDexTradeEntry(dexTradeEntryWrite);    
            // storedEntries.add(dexTradeEntryWrite);
        }
        
    }
    
    void tick() {
        
    }
    
    @Override
    public void start() {
        
            log.debug("Creating test : ");
            dao.hardDeleteAllDexTrade();            
            createTestEntry();            
            log.debug("Trading view service startup point: ");
            
            done = false;
            
            // detecting the point of starting up... 
            
           
         
            Runnable task = () -> {
                for(;;) {   
                    
                    // log.debug("trying to pour in the database: ");
                    
                    
                    try {                        
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        log.error( "Runnable exception: {} ", ex.toString() );
                    }                    
                    this.tick();                   
                    if (done) break;
                }
                
            };
            Thread thread = new Thread(task);
            thread.start();

        
        
    }

    @Override
    public void stop() {
        this.done = true; 

    }
    
}
