/*
 * Copyright (C) 2013 Intel Corporation
 * All rights reserved.
 */
package com.intel.dcsg.cpg.tls.policy;

import com.intel.dcsg.cpg.tls.policy.impl.AllowAllX509TrustManager;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import com.intel.dcsg.cpg.x509.repository.*;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509TrustManager;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class exists to coordinate the use of separate TLS Policy instances for separate connections, where the
 * connections are being made by client code that we don't control, using URL.openConnection() or URL.openStream(),
 * which uses the global default trust manager and hostname verifier.
 *
 * To use this class, register the TLS Policy for each server before you open a connection to it.
 *
 * @author jbuhacoff
 */
public class TlsPolicyManager implements TlsPolicy, HostnameVerifier {

    private static final TlsPolicyManager singleton = new TlsPolicyManager();

    public static TlsPolicyManager getInstance() {
        return singleton;
    }
    private static Logger log = LoggerFactory.getLogger(TlsPolicyManager.class);
    private ConcurrentHashMap<String, TlsPolicy> map = new ConcurrentHashMap<String, TlsPolicy>(32); // default capacity 16 but we're starting with 32, default load factor 0.75

    public void setTlsPolicy(String address, TlsPolicy tlsPolicy) {
        log.debug("TlsPolicyManager: adding {} with policy: {}", address, tlsPolicy.getClass().toString());
        map.put(address, tlsPolicy);
    }

    @Override
    public X509TrustManager getTrustManager() {
//        log.debug("TlsPolicyManager: providing allow-all X509TrustManager");
        return new AllowAllX509TrustManager();
    }

    @Override
    public HostnameVerifier getHostnameVerifier() {
//        log.debug("TlsPolicyManager: providing HostnameVerifier (this)");
        return this;
    }

    @Override
    public CertificateRepository getCertificateRepository() {
        ArrayList<X509Certificate> list = new ArrayList<X509Certificate>();
        for (TlsPolicy tlsPolicy : map.values()) {
            list.addAll(tlsPolicy.getCertificateRepository().getCertificates());
        }
        return new ArrayCertificateRepository(list.toArray(new X509Certificate[list.size()]));
    }

    @Override
    public boolean verify(String address, SSLSession ssls) {
        // look up the TLS Policy for the host

        TlsPolicy tlsPolicy = map.get(address);
        if (tlsPolicy == null) {
            throw new TlsPolicyException("No TLS policy for host", address, null, null);
        }
        log.debug("TlsPolicyManager: policy {} for host: {}", tlsPolicy.getClass().getName(), address);
        // get the list of X509 certificates from the remote server
        ArrayList<X509Certificate> x509list = new ArrayList<X509Certificate>();
        try {
            Certificate[] certificates = ssls.getPeerCertificates(); // throws SSLPeerUnverifiedException
            for (Certificate cert : certificates) {
                //                log.debug("TlsPolicyManager: verify: certificate {} is {}", cert.getType(), DigestUtils.sha(cert.getEncoded())); // throws CertificateEncodingException
                if (cert.getType() != null && cert.getType().equals("X.509")) {
                    //                    x509list.add( X509Util.decodeDerCertificate(cert.getEncoded()) );
                    x509list.add((X509Certificate) cert);
                }
            }
        } catch (SSLPeerUnverifiedException e) {
            throw new TlsPolicyException("Peer cannot be verified", address, tlsPolicy, null);
        }
        X509Certificate[] serverCertificates = x509list.toArray(new X509Certificate[x509list.size()]);
        // verify peer certificates (since we skipped it in the trust manager -- because there we don't have the context of which host we are connecting to in order to look up the appropriate tls policy)
        X509TrustManager trustManager = tlsPolicy.getTrustManager();
        try {
            trustManager.checkServerTrusted(serverCertificates, "RSA");
            log.debug("TlsPolicyManager: Server certificate is trusted: {}", address);
        } catch (CertificateException e) {
            log.error("TlsPolicyManager: Server certificate not trusted: {}: " + e.toString(), address, e);
//                return false; // looks like this: java.io.IOException: HTTPS hostname wrong:  should be <10.1.71.201>
            throw new TlsPolicyException("Server certificate is not trusted", address, tlsPolicy, serverCertificates); // looks like this: com.intel.mtwilson.tls.ServerCertificateNotTrustedException: TlsPolicyManager: Server certificate is not trusted
        }
        log.debug("TlsPolicyManager: using policy for {}", address);
        // if the peer certificate is ok, verify the hostname:
        boolean hostnameVerified = tlsPolicy.getHostnameVerifier().verify(address, ssls); // XXX TODO need to create a seaprate instance for each connection, using the         llllllo-ktls policy for that connection
        log.debug("TlsPolicyManager: Hostname verification {} for {}", hostnameVerified ? "passed" : "failed", address);
        if (hostnameVerified) {
            return true;
        }
//            return hostnameVerified; // looks like this: java.io.IOException: HTTPS hostname wrong:  should be <10.1.71.201>
        // but instead of returning false, we're going to throw our custom exception that also provides the tls policy in effect
        throw new TlsPolicyException("Hostname verification failed", address, tlsPolicy, serverCertificates); // looks like this: com.intel.mtwilson.tls.ServerCertificateNotTrustedException: TlsPolicyManager: Hostname verification failed: Should be <10.1.71.201>
    }

    @Override
    public boolean providesConfidentiality() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean providesAuthentication() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean providesIntegrity() {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
