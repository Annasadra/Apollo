/*
 * Copyright © 2013-2016 The Nxt Core Developers.
 * Copyright © 2016-2017 Jelurida IP B.V.
 *
 * See the LICENSE.txt file at the top-level directory of this distribution
 * for licensing information.
 *
 * Unless otherwise agreed in a custom licensing agreement with Jelurida B.V.,
 * no part of the Nxt software, including this file, may be copied, modified,
 * propagated, or distributed except according to the terms contained in the
 * LICENSE.txt file.
 *
 * Removal or modification of this copyright notice is prohibited.
 *
 */

/*
 * Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.http.post;

import com.apollocurrency.aplwallet.apl.core.app.Generator;
import com.apollocurrency.aplwallet.apl.core.http.APITag;
import com.apollocurrency.aplwallet.apl.core.http.AbstractAPIRequestHandler;
import com.apollocurrency.aplwallet.apl.core.http.HttpParameterParserUtil;
import com.apollocurrency.aplwallet.apl.core.http.ParameterException;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import javax.enterprise.inject.Vetoed;
import javax.servlet.http.HttpServletRequest;

@Vetoed
public final class StopForging extends AbstractAPIRequestHandler {

    public StopForging() {
        super(new APITag[]{APITag.FORGING}, "secretPhrase", "adminPassword");
    }

    @Override
    public JSONStreamAware processRequest(HttpServletRequest req) throws ParameterException {
        long accountId = HttpParameterParserUtil.getAccountId(req, vaultAccountName(), false);
        byte[] keySeed = HttpParameterParserUtil.getKeySeed(req, accountId, false);
        JSONObject response = new JSONObject();
        if (keySeed != null) {
            Generator generator = Generator.stopForging(keySeed);
            response.put("foundAndStopped", generator != null);
            response.put("forgersCount", Generator.getGeneratorCount());
        } else {
            apw.verifyPassword(req);
            int count = Generator.stopForging();
            response.put("stopped", count);
        }
        return response;
    }

    @Override
    protected boolean requirePost() {
        return true;
    }

    @Override
    protected boolean allowRequiredBlockParameters() {
        return false;
    }

    @Override
    protected boolean requireFullClient() {
        return true;
    }


    @Override
    protected boolean is2FAProtected() {
        return true;
    }

    @Override
    protected String vaultAccountName() {
        return "account";
    }
}
