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
 * Copyright © 2018 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.peer;

import com.apollocurrency.aplwallet.apl.core.http.APIEnum;
import com.apollocurrency.aplwallet.apl.util.Version;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import java.util.Set;
import java.util.UUID;

public interface Peer extends Comparable<Peer> {

    boolean providesService(Service service);

    boolean providesServices(long services);

    String getHost();

    int getPort();

    String getHostWithPort();

    String getAnnouncedAddress();

    PeerState getState();

    Version getVersion();

    String getApplication();

    String getPlatform();

    String getSoftware();

    int getApiPort();

    int getApiSSLPort();

    Set<APIEnum> getDisabledAPIs();

    int getApiServerIdleTimeout();

    BlockchainState getBlockchainState();

    Hallmark getHallmark();

    int getWeight();

    boolean shareAddress();

    boolean isBlacklisted();

    void blacklist(Exception cause);

    void blacklist(String cause);

    void unBlacklist();

    void deactivate(String reason);

    void remove();

    long getDownloadedVolume();

    long getUploadedVolume();

    int getLastUpdated();

    int getLastConnectAttempt();

    boolean isInbound();

    boolean isOutbound();

    boolean isInboundSocket();

    boolean isOutboundSocket();

    boolean isOpenAPI();

    boolean isApiConnectable();

    UUID getChainId();

    StringBuilder getPeerApiUri();

    String getBlacklistingCause();

    JSONObject send(JSONStreamAware request, UUID chainId) throws PeerNotConnectedException;

    public boolean isTrusted();

    public PeerTrustLevel getTrustLevel();

    public long getServices();

    enum Service {
        HALLMARK(1),                    // Hallmarked node
        PRUNABLE(2),                    // Stores expired prunable messages
        API(4),                         // Provides open API access over http
        API_SSL(8),                     // Provides open API access over https
        CORS(16);                       // API CORS enabled

        private final long code;        // Service code - must be a power of 2

        Service(int code) {
            this.code = code;
        }

        public long getCode() {
            return code;
        }
    }
}
