/*
 *  Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.shuffling.service;

import java.util.Arrays;

public enum Stage {
    REGISTRATION((byte)0, new byte[]{1,4}),
    PROCESSING((byte)1, new byte[]{2,3,4}),
    VERIFICATION((byte)2, new byte[]{3,4,5}),
    BLAME((byte)3, new byte[]{4}),
    CANCELLED((byte)4, new byte[]{}),
    DONE((byte) 5, new byte[] {});

    private final byte code;
    private final byte[] allowedNext;

    Stage(byte code, byte[] allowedNext) {
        this.code = code;
        this.allowedNext = allowedNext;
    }

    public static Stage get(byte code) {
        for (Stage stage : Stage.values()) {
            if (stage.code == code) {
                return stage;
            }
        }
        throw new IllegalArgumentException("No matching stage for " + code);
    }

    public byte getCode() {
        return code;
    }

    public boolean canBecome(Stage nextStage) {
        return Arrays.binarySearch(allowedNext, nextStage.code) >= 0;
    }

}