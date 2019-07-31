/*
 * Copyright © 2018-2019 Apollo Foundation
 */
package com.apollocurrency.aplwallet.apl.util.task;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class BackgroundTaskDispatcher extends AbstractTaskDispatcher {

    public BackgroundTaskDispatcher(String serviceName) {
        super(new ScheduledExecutorServiceFactory(), serviceName);
    }

    @Override
    public boolean validate(Task task) {
        return !(task.getTask() == null || task.getName() == null);
    }

    @Override
    public void invoke(Task task){
        try {
            ((ScheduledExecutorService) createMainExecutor())
                    .scheduleWithFixedDelay(task, task.getInitialDelay(), task.getDelay(), TimeUnit.MILLISECONDS);
        }catch (Exception e){
            log.error("The task [{}] can't be scheduled, cause:{}", task, e.getMessage());
        }
    }
}
