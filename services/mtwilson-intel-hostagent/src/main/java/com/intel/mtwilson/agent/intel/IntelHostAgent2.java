/*
 * Copyright (C) 2012 Intel Corporation
 * All rights reserved.
 */
package com.intel.mtwilson.agent.intel;

import com.intel.dcsg.cpg.io.UUID;
import com.intel.mtwilson.trustagent.model.HostInfo;
import com.intel.mtwilson.agent.HostAgent;
import com.intel.dcsg.cpg.x509.X509Util;
import com.intel.mtwilson.datatypes.TxtHostRecord;
import com.intel.mtwilson.model.Aik;
import com.intel.mtwilson.model.InternetAddress;
import com.intel.mtwilson.model.Nonce;
import com.intel.mtwilson.model.PcrIndex;
import com.intel.mtwilson.model.PcrManifest;
import com.intel.mtwilson.model.TpmQuote;
import com.intel.mtwilson.trustagent.client.jaxrs.TrustAgentClient;
import java.io.IOException;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instances of VmwareAgent should be created by the VmwareAgentFactory
 * @author jbuhacoff
 */
public class IntelHostAgent2 implements HostAgent {
    private transient Logger log = LoggerFactory.getLogger(getClass());
    private transient final TrustAgentClient client;
//    private final String hostname;
    private InternetAddress hostAddress;
    private Boolean isTpmAvailable = null;
    private String vendorHostReport = null;
    private String vmmName = null;
    private PcrManifest pcrManifest = null;
    
    public IntelHostAgent2(TrustAgentClient client, InternetAddress hostAddress) throws Exception {
        this.client = client;
        this.hostAddress = hostAddress;
//        this.hostname = hostname;
    }
    
    
    
    @Override
    public boolean isTpmPresent() {
//        throw new UnsupportedOperationException("Not supported yet.");
        // bug #538  for now assuming all trust-agent hosts have tpm since we don't have a separate capabilities call
        return true; 
    }

    @Override
    public boolean isTpmEnabled() {
        return true; 
    }

    @Override
    public boolean isEkAvailable() {
        return false; // vmware does not make the EK available through its API
    }

    @Override
    public boolean isAikAvailable() { 
        return true;  // assume we can always get an AIK from a trust agent,  for now
    }

    @Override
    public boolean isAikCaAvailable() { 
        return true; // assume hosts running trust agent always use a privacy ca,  for now
    }

    @Override
    public boolean isDaaAvailable() {
        return false; // intel trust agent currently does not support DAA
    }

    @Override
    public X509Certificate getAikCertificate() {
        try {
            X509Certificate aik = client.getAik();
            isTpmAvailable = true;
            return aik;
        }
        catch(Exception e) {
            log.debug("Cannot retrieve AIK certificate: {}", e.toString(), e);
            return null;
        }
    }

    @Override
    public X509Certificate getAikCaCertificate() {
        try {
            X509Certificate privacyCA = client.getAikCa();
            isTpmAvailable = true;
            return privacyCA;
        }
        catch(Exception e) {
            log.debug("Cannot retrieve Privacy CA certificate: {}", e.toString(), e);
            return null;
        }
    }

    @Override
    public String getHostInformation() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String getVendorHostReport()  throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }
    
    @Override
    public TpmQuote getTpmQuote(Aik aik, Nonce nonce, Set<PcrIndex> pcr) {
        throw new UnsupportedOperationException("Not supported  yet."); 
    }


    @Override
    public PcrManifest getPcrManifest() throws IOException {
        if( pcrManifest == null ) {
            try {
                TAHelper helper = new TAHelper();
                pcrManifest = helper.getQuoteInformationForHost(hostAddress.toString(), client); 
            }
            catch(Exception e) {
                throw new IOException("Cannot retrieve PCR Manifest from "+hostAddress.toString(), e);
            }
        }
        return pcrManifest;
    }

    @Override
    public TxtHostRecord getHostDetails() throws IOException {
        HostInfo hostInfo = client.getHostInfo();
        TxtHostRecord host = new TxtHostRecord();
        host.BIOS_Name = hostInfo.getBiosOem().trim(); 
        host.BIOS_Oem = hostInfo.getBiosOem().trim();
        host.BIOS_Version = hostInfo.getBiosVersion().trim();
        host.VMM_Name = hostInfo.getVmmName().trim();
        host.VMM_Version = hostInfo.getVmmVersion().trim();
        host.VMM_OSName = hostInfo.getOsName().trim();
        host.VMM_OSVersion = hostInfo.getOsVersion().trim();
        //  The actual processor information includes the below 8 byte data out of which only the first 3 bytes are needed to match the processor generation
        // C2 06 02 00 FF FB EB BF
        host.Processor_Info = hostInfo.getProcessorInfo().trim().substring(0, 8).toUpperCase();
        // now set some state we need for getHostAttestationReport
        vmmName = host.VMM_Name;
        return host;
    }

    @Override
    public String getHostAttestationReport(String pcrList) throws IOException {
        if( vendorHostReport != null ) { return vendorHostReport; }
        if( vmmName == null ) { getHostDetails(); }
//        throw new UnsupportedOperationException("Not supported yet.");
//        OpenSourceVMMHelper helper = new OpenSourceVMMHelper();
//        return help.getHostAttestationReport(hostAddress);
        try {
            TAHelper helper = new TAHelper();
            // currently the getHostAttestationReport function is ONLY called from Management Service HostBO.configureWhiteListFromCustomData(...)  so there wouldn't be any saved trusted AIK in the database anyway
            pcrManifest = helper.getQuoteInformationForHost(hostAddress.toString(), client);
            vendorHostReport = helper.getHostAttestationReport(hostAddress.toString(), pcrManifest, vmmName);
            log.debug("Host attestation report for {}", hostAddress);
            log.debug(vendorHostReport);
            return vendorHostReport;
        }
        catch(Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean isIntelTxtSupported() {
        return true; 
    }

    @Override
    public boolean isIntelTxtEnabled() {
        return true; 
    }

    @Override
    public PublicKey getAik() {
        X509Certificate aikcert = getAikCertificate();
        return aikcert.getPublicKey();
    }

    @Override
    public X509Certificate getEkCertificate() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Map<String, String> getHostAttributes() throws IOException {
       HashMap<String,String> hm = new HashMap<String, String>();
        // Retrieve the data from the host and add it into the hashmap
        HostInfo hostInfo = client.getHostInfo();
        // Currently we are just adding the UUID of th host. Going ahead we can add additional details
        if (hostInfo != null)
            hm.put("Host_UUID", hostInfo.getHardwareUuid().trim());
        
        return hm;
    }
    
    @Override
    public void setAssetTag(com.intel.dcsg.cpg.crypto.Sha1Digest tag) throws IOException {
        Map<String, String> hm = getHostAttributes();
        log.debug("calling trustAgentClient with " + tag.toHexString() + " | " +  hm.get("Host_UUID"));
        //trustAgentClient.setAssetTag(tag.toHexString(), hm.get("Host_UUID"));
        client.writeTag(tag.toByteArray(), UUID.valueOf(hm.get("Host_UUID")));
    }
}
