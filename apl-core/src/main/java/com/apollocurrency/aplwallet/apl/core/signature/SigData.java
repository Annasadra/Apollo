/*
 * Copyright (c)  2018-2020. Apollo Foundation.
 */

package com.apollocurrency.aplwallet.apl.core.signature;

import com.apollocurrency.aplwallet.apl.crypto.Convert;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONObject;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * @author andrii.zinchenko@firstbridge.io
 */
@Slf4j
class SigData implements Signature {
    private final byte[] signature;
    private final SignatureParser parser = new SigData.Parser();
    private boolean verified = false;

    public SigData(byte[] signature) {
        this.signature = Objects.requireNonNull(signature);
    }

    void setVerified(boolean verified) {
        this.verified = verified;
    }

    @Override
    public boolean isVerified() {
        return verified;
    }

    @Override
    public byte[] bytes() {
        return signature;
    }

    @Override
    public int getSize() {
        return signature.length;
    }

    @Override
    public String getJsonString() {
        return Convert.toHexString(signature);
    }

    @Override
    public JSONObject getJsonObject() {
        return parser.getJsonObject(this);
    }

    static class Parser implements SignatureParser {
        private static final int PARSER_VERSION = 1;

        /**
         * Parse the byte array and build the multisig object
         *
         * @param buffer input data array
         * @return the multisig object
         */
        @Override
        public Signature parse(ByteBuffer buffer) {
            SigData sigData;
            try {
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                byte[] signature = new byte[ECDSA_SIGNATURE_SIZE];
                buffer.get(signature);
                sigData = new SigData(signature);
            } catch (BufferUnderflowException e) {
                String message = "Can't parse signature bytes, cause: " + e.getMessage();
                log.error(message);
                throw new SignatureParseException(message);
            }
            return sigData;
        }

        @Override
        public int calcDataSize(int count) {
            return ECDSA_SIGNATURE_SIZE;
        }

        @Override
        public byte[] bytes(Signature signature) {
            ByteBuffer buffer = ByteBuffer.allocate(calcDataSize(1));
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(signature.bytes());
            return buffer.array();
        }

        @Override
        public JSONObject getJsonObject(Signature signature) {
            JSONObject json = new JSONObject();
            json.put(SIGNATURE_FIELD_NAME, Convert.toHexString(signature.bytes()));
            return json;
        }

        @Override
        public Signature parse(JSONObject json) {
            byte[] signature = Convert.parseHexString((String) json.get(SIGNATURE_FIELD_NAME));
            return new SigData(signature);
        }
    }
}