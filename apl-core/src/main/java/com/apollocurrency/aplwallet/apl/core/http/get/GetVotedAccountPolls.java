/*
 * Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.http.get;

import com.apollocurrency.aplwallet.apl.core.app.Poll;
import com.apollocurrency.aplwallet.apl.core.db.DbIterator;
import com.apollocurrency.aplwallet.apl.core.http.APITag;
import com.apollocurrency.aplwallet.apl.core.http.AbstractAPIRequestHandler;
import com.apollocurrency.aplwallet.apl.core.http.HttpParameterParserUtil;
import com.apollocurrency.aplwallet.apl.core.http.JSONData;
import com.apollocurrency.aplwallet.apl.util.AplException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import javax.enterprise.inject.Vetoed;
import javax.servlet.http.HttpServletRequest;

@Vetoed
public class GetVotedAccountPolls extends AbstractAPIRequestHandler {
    public GetVotedAccountPolls() {
        super(new APITag[]{APITag.VS, APITag.ACCOUNTS}, "account", "firstIndex", "lastIndex");
    }

    @Override
    public JSONStreamAware processRequest(HttpServletRequest request) throws AplException {
        long account = HttpParameterParserUtil.getAccountId(request, true);
        int firstIndex = HttpParameterParserUtil.getFirstIndex(request);
        int lastIndex = HttpParameterParserUtil.getLastIndex(request);
        JSONObject response = new JSONObject();
        JSONArray pollJsonArray = new JSONArray();
        try (DbIterator<Poll> pollDbIterator = Poll.getVotedPollsByAccount(account, firstIndex, lastIndex)) {
            while (pollDbIterator.hasNext()) {
                pollJsonArray.add(JSONData.poll(pollDbIterator.next()));
            }
        }
        response.put("polls", pollJsonArray);
        return response;
    }
}
