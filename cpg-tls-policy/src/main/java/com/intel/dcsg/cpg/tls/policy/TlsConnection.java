/*
 * Copyright (C) 2013 Intel Corporation
 * All rights reserved.
 */
package com.intel.dcsg.cpg.tls.policy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

/**
 *
 * @author jbuhacoff
 */
public class TlsConnection {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TlsConnection.class);

    private final URL url;
    private final TlsPolicy tlsPolicy;
    private transient Integer hashCode = null;

    public TlsConnection(URL url, TlsPolicy tlsPolicy) {
        this.url = url;
        this.tlsPolicy = tlsPolicy;
    }

    public URL getURL() {
        return url;
    }

    public TlsPolicy getTlsPolicy() {
        return tlsPolicy;
    }
    
    /**
     * Caller must close socket when done.
     * 
     * 
     * @param timeoutMilliseconds
     * @return
     * @throws IOException
     * @throws NoSuchAlgorithmException
     * @throws KeyManagementException 
     * @throws SSLPeerUnverifiedException if destination hostname does not match certificate (socket will be automatically closed)
     */
    public SSLSocket connect(int timeoutMilliseconds) throws IOException, NoSuchAlgorithmException, KeyManagementException {
    	SSLContext ctx = SSLContext.getInstance("SSL");
        ctx.init(null, new javax.net.ssl.TrustManager[]{ tlsPolicy.getTrustManager() }, null);
        SSLSocketFactory sslsocketfactory = ctx.getSocketFactory();
        SSLSocket socket = (SSLSocket) sslsocketfactory.createSocket();
        socket.connect(new InetSocketAddress(url.getHost(),port()), timeoutMilliseconds);
        if( !tlsPolicy.getHostnameVerifier().verify(url.getHost(), socket.getSession()) ) {
            socket.close();        
            throw new SSLPeerUnverifiedException("Invalid certificate for address: "+url.getHost());
        }
        return socket;
    }
    
    /**
     * No timeout.
     * 
     * Caller must close socket when done.
     * 
     * @return
     * @throws IOException
     * @throws NoSuchAlgorithmException
     * @throws KeyManagementException 
     * @throws SSLPeerUnverifiedException if destination hostname does not match certificate (socket will be automatically closed)
     */
    public SSLSocket connect() throws IOException, NoSuchAlgorithmException, KeyManagementException {
    	SSLContext ctx = SSLContext.getInstance("SSL");
        ctx.init(null, new javax.net.ssl.TrustManager[]{ tlsPolicy.getTrustManager() }, null);
        SSLSocketFactory sslsocketfactory = ctx.getSocketFactory();
        SSLSocket socket = (SSLSocket) sslsocketfactory.createSocket();
        socket.connect(new InetSocketAddress(url.getHost(),port()));
        if( !tlsPolicy.getHostnameVerifier().verify(url.getHost(), socket.getSession()) ) {
            socket.close();        
            throw new SSLPeerUnverifiedException("Invalid certificate for address: "+url.getHost());
        }
        return socket;
    }
    
    private int port() {
        int port = url.getPort();
        if( port == -1 ) {
            port = url.getDefaultPort();
            if( port == -1 ) {
                log.warn("No port defined in URL for {} to {}", url.getProtocol(), url.getHost());
            }
        }
        return port;
    }

    /**
     * The following elements are considered when calculating hashCode: 
     * 1. url protocol
     * 2. url host
     * 3. url port
     * 4. tls policy name (obtained from the implementation
     * class name) 
     * 5. contents of certificate repository
     *
     * @return
     */
    @Override
    public int hashCode() {
        if (hashCode != null) {
            return hashCode;
        }
        return new HashCodeBuilder(19, 47)
                .append(url.getProtocol())
                .append(url.getHost())
                .append(url.getPort())
                .append(tlsPolicy.getClass().getName())
                .append(tlsPolicy.getCertificateRepository())
                .toHashCode();
    }

    /**
     * Two TlsConnection instances are equal if they have the same URLs the
     * same TlsPolicy, and the same contents in their CertificateRepository. 
     * Note: the URLs are compared using protocol, host, and port; all other parameters
     * are not considered. 
     * the same 
     * @param other
     * @return 
     */
    @Override
    public boolean equals(Object other) {
        if (other == null) {
            return false;
        }
        if (other == this) {
            return true;
        }
        if (other.getClass() != this.getClass()) {
            return false;
        }
        TlsConnection rhs = (TlsConnection) other;
        return new EqualsBuilder()
                .append(url.getProtocol(), rhs.url.getProtocol())
                .append(url.getHost(), rhs.url.getHost())
                .append(url.getPort(), rhs.url.getPort())
                .append(tlsPolicy.getClass().getName(), rhs.tlsPolicy.getClass().getName())
                .append(tlsPolicy.getCertificateRepository(), rhs.tlsPolicy.getCertificateRepository())
                .isEquals(); // XXX TODO see note in hashCode about comparing the tls policy
    }
}
