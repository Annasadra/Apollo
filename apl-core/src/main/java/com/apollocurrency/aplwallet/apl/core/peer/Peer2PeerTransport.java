/*
 * Copyright © 2018 Apollo Foundation
 */
package com.apollocurrency.aplwallet.apl.core.peer;

import com.apollocurrency.aplwallet.apl.core.peer.endpoint.Errors;
import com.apollocurrency.aplwallet.apl.util.CountingInputReader;
import com.apollocurrency.aplwallet.apl.util.CountingOutputWriter;
import com.apollocurrency.aplwallet.apl.util.StringUtils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.ref.SoftReference;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

/**
 *
 * @author alukin@gmail.com
 */
@Slf4j
public class Peer2PeerTransport {

    private final SoftReference<Peer> peerReference;
    /**
     * map requests to responses
     */
    private final ConcurrentHashMap<Long, ResonseWaiter> requestMap = new ConcurrentHashMap<>();
    private final Random rnd;
    private final PeerServlet peerServlet;
    private final boolean useWebSocket = Peers.useWebSockets && !Peers.useProxy;
    private volatile long downloadedVolume;
    private volatile long uploadedVolume;
    private final Object volumeMonitor = new Object();
    private PeerWebSocket inboundWebSocket;
    private PeerWebSocketClient outboundWebSocket;

    //we use random numbers to minimize possible request/response mismatches
    private Long nextRequestId() {
        Long res = rnd.nextLong();
        if (res == 0L) { // make sure we do not use 0 value
            res++;
        }
        return res;
    }

    public Peer getPeer() {
        return peerReference.get();
    }

    String which() {
        String which;
        if (peerServlet != null) {
            which = "Inbound";
        } else {
            which = "Outbound";
        }
        Peer p = peerReference.get();
        if (p != null) {
            which += " at " + p.getHostWithPort();
        }
        return which;
    }

    public Peer2PeerTransport(Peer peer, PeerServlet peerServlet) {
        this.peerReference = new SoftReference<>(peer);
        this.peerServlet = peerServlet;
        rnd = new Random(System.currentTimeMillis());
    }

    public long getDownloadedVolume() {
        return downloadedVolume;
    }

    void updateDownloadedVolume(long volume) {
        synchronized (volumeMonitor) {
            downloadedVolume += volume;
        }
        Peers.notifyListeners(getPeer(), Peers.Event.DOWNLOADED_VOLUME);
    }

    public long getUploadedVolume() {
        return uploadedVolume;
    }

    void updateUploadedVolume(long volume) {
        synchronized (volumeMonitor) {
            uploadedVolume += volume;
        }
        Peers.notifyListeners(getPeer(), Peers.Event.UPLOADED_VOLUME);
    }

    public void onIncomingMessage(String message, PeerWebSocket ws, Long rqId) {
        ResonseWaiter wsrw = requestMap.get(rqId);
        if (wsrw != null) { //this is response
            wsrw.setResponse(message);
        } else { //most likely ge've got request from remote and should process it
            peerServlet.doPost(this, rqId, message);
        }
    }

    public Long sendRequest(String message){
        Long requestId = nextRequestId();
        requestMap.put(requestId, new ResonseWaiter());
        boolean sendOK = send(message, requestId);
        if(sendOK) {
          return requestId;
        }else{
          return null;
        }
    }

    public String sendAndWaitResponse(String request) {
        String res = null;
        Long rqId;
        boolean sendOK = true;
        rqId = sendRequest(request);
        if(rqId==null){
            log.debug("Exception while sending to websocket of {}", which());
            sendOK = false;
        }
        if (sendOK) {
           res = getResponse(rqId);
        }
        return res;
    }

    public String getResponse(Long rqId){
        String res = null;
        ResonseWaiter wsrw = requestMap.get(rqId);
        if (wsrw != null) {
            try {
                res = wsrw.get(Peers.readTimeout);
            } catch (SocketTimeoutException ex) {
                log.warn("Timeout excided while waiting response from: {} ID: {}",which(),rqId);
            }
            requestMap.remove(rqId);
        }else{
            log.error("Waiting for non-exisatent request. Peer: {}, ID: {}",which(),rqId);
        }
        return res;
    }

    public String getHostWithPort() {
        String res = "";
        Peer p = peerReference.get();
        if (p != null) {
            res = p.getHostWithPort();
        }
        return res;
    }

    public void onWebSocketClose(PeerWebSocket ws) {

    }

    private synchronized void cleanUp() {
        List<Long> toDelete = new ArrayList<>();
        requestMap.keySet().stream().filter((wsw) -> (requestMap.get(wsw).isOld())).forEachOrdered((wsw) -> {
            toDelete.add(wsw);
        });
        toDelete.forEach((key) -> {
            requestMap.remove(key);
        });
    }

    private boolean sendHttp(final String request, Long requestId) {
        boolean sendOK = false;
        HttpURLConnection connection;
        String urlString = "http://" + getHostWithPort() + "/apl";
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(Peers.connectTimeout);
            connection.setReadTimeout(Peers.readTimeout);
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");
            try (Writer writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(), "UTF-8"))) {
                CountingOutputWriter cow = new CountingOutputWriter(writer);
                cow.write(request);
                updateUploadedVolume(cow.getCount());
            }
        } catch (IOException ex) {
            log.trace("Error sending HTTP erequest to {}", getHostWithPort(), ex);
            return sendOK;
        }
        try {
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                InputStream responseStream = connection.getInputStream();
                if ("gzip".equals(connection.getHeaderField("Content-Encoding"))) {
                    responseStream = new GZIPInputStream(responseStream);
                }
                try (Reader reader = new BufferedReader(new InputStreamReader(responseStream, "UTF-8"))) {
                    CountingInputReader cir = new CountingInputReader(reader, Peers.MAX_RESPONSE_SIZE);
                    updateDownloadedVolume(cir.getCount());
                    StringWriter sw = new StringWriter(1000);
                    cir.transferTo(sw);
                    String response = sw.toString();
                    onIncomingMessage(response, null, requestId);
                    sendOK = true;
                }
            } else {
                log.debug("Peer " + getHostWithPort() + " responded with HTTP " + connection.getResponseCode());
            }
        } catch (IOException ex) {
            log.warn("Error getting HTTP response from {}", getHostWithPort(), ex); 
        }finally{
            connection.disconnect(); 
        }
        return sendOK;

    }

    private boolean sendToWebSocket(final String wsRequest, PeerWebSocket ws, Long requestId) {
        boolean sendOK = false;
        try {
            if (ws == null) {
                log.debug("null websocket");
                return sendOK;
            }
            sendOK = ws.send(wsRequest, requestId);
        } catch (IOException ex) {
            log.debug("Can not sent to {}. Exception: {}", getHostWithPort(), ex);
        }
        return sendOK;
    }

    public boolean send(String message, Long requestId) {
        cleanUp();
            boolean sendOK = false;
            if (useWebSocket) {
                if (isInbound()) {
                    sendOK  = sendToWebSocket(message, inboundWebSocket, requestId);

                    if (!sendOK ) {
                        log.trace("Peer: {} Using inbound web socket. failed. Closing", getHostWithPort());
                        inboundWebSocket.close();
                        inboundWebSocket = null;
                    } else {
                        log.trace("Peer: {} Send using inbound web socket failed", getHostWithPort());
                    }
                }
                if (!sendOK) { //no inbound connection or send failed
                    if (outboundWebSocket == null) {
                        outboundWebSocket = new PeerWebSocketClient(this);
                    }
                    if (!outboundWebSocket.isConnected()) {
                        // Create a new WebSocket session if we don't have one
                        // and do not have inbound
                        String addrWithPort = peerReference.get().getAnnouncedAddress();
                        if (StringUtils.isBlank(addrWithPort)) { // try to use addres with port, should be OK for default peers
                            addrWithPort = getHostWithPort();
                        }
                        String wsConnectString = "ws://" + addrWithPort + "/apl";
                        URI wsUri = URI.create(wsConnectString);
                        log.trace("Connecting to websocket'{}'...", wsConnectString);
                        sendOK  = outboundWebSocket.startClient(wsUri);
                        if (sendOK ) {
                            log.trace("Connected as client to websocket {}", wsConnectString);
                        }
                    } else { //client socket is already open
                       sendOK = true;
                    }
                    if (sendOK) { //send using client socket
                        sendOK = sendToWebSocket(message, outboundWebSocket, requestId);
                    }
                }
            }
            if (!sendOK) { // Send the request using HTTP as fallback
                sendOK = sendHttp(message,requestId);
                if(!sendOK){
                    log.debug("Peer: {} Using HTTP. Failed.", getHostWithPort());
                }
            }
            if(!sendOK){
                String msg ="Error on sending request";
                getPeer().blacklist(msg);
            }
        return sendOK;
    }

    void disconnect() {
        if (inboundWebSocket != null) {
            inboundWebSocket.close();
            inboundWebSocket = null;
        }
        if (outboundWebSocket != null) {
            outboundWebSocket.close();
            outboundWebSocket = null;
        }

    }

    boolean isInbound() {
        boolean res = inboundWebSocket != null && inboundWebSocket.isConnected();
        return res;
    }

    boolean isOutbound() {
        boolean res = outboundWebSocket != null && outboundWebSocket.isConnected();
        return res;
    }

}
