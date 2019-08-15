/*
 * Copyright © 2018-2019 Apollo Foundation
 */
package com.apollocurrency.aplwallet.apl.util.task;

/**
 * An enum to organize the task execution order.
 * An {@link TaskDispatcher} keeps to this order to execute tasks.
 * <p><ul>
 * <li>{@code INIT} - the initial task, will be run at first
 * <li>{@code BEFORE} - this task will be run before the main task
 * <li>{@code TASK} - this is the main periodical task
 * <li>{@code AFTER} - this task will be run after the main task started
 * <ul/>
 */
public enum TaskOrder {
    INIT,
    BEFORE,
    TASK,
    AFTER
}
