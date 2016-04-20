package com.intel.mtwilson.as.business.trust;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intel.mtwilson.i18n.ErrorCode;
import com.intel.mountwilson.as.common.ASConfig;
import com.intel.mountwilson.as.common.ASException;
import com.intel.mtwilson.My;
import com.intel.mtwilson.agent.*;
import com.intel.mtwilson.as.business.AssetTagCertBO;
import com.intel.mtwilson.as.business.HostBO;
import com.intel.mtwilson.as.controller.TblLocationPcrJpaController;
import com.intel.mtwilson.as.controller.TblMleJpaController;
import com.intel.mtwilson.as.controller.TblModuleManifestLogJpaController;
import com.intel.mtwilson.as.controller.TblTaLogJpaController;
import com.intel.mtwilson.as.data.MwAssetTagCertificate;
import com.intel.mtwilson.as.data.TblHosts;
import com.intel.mtwilson.as.data.TblLocationPcr;
import com.intel.mtwilson.as.data.TblMle;
import com.intel.mtwilson.as.data.TblModuleManifestLog;
import com.intel.mtwilson.as.data.TblSamlAssertion;
import com.intel.mtwilson.as.data.TblTaLog;
import com.intel.mtwilson.saml.SamlAssertion;
import com.intel.mtwilson.saml.SamlGenerator;
import com.intel.mtwilson.tag.model.X509AttributeCertificate;
import com.intel.mtwilson.audit.api.AuditLogger;
import com.intel.dcsg.cpg.crypto.CryptographyException;
import com.intel.mtwilson.datatypes.*;
import com.intel.dcsg.cpg.io.FileResource;
import com.intel.dcsg.cpg.io.Resource;
import com.intel.dcsg.cpg.io.UUID;
import com.intel.mtwilson.as.controller.exceptions.ASDataException;
import com.intel.mtwilson.as.controller.exceptions.IllegalOrphanException;
import com.intel.mtwilson.as.controller.exceptions.NonexistentEntityException;
import com.intel.mtwilson.as.rest.v2.model.HostAttestation;
import com.intel.mtwilson.jaxrs2.provider.JacksonObjectMapperProvider;
import com.intel.mtwilson.model.*;
import com.intel.mtwilson.policy.Fault;
import com.intel.mtwilson.policy.HostReport;
import com.intel.mtwilson.policy.Policy;
import com.intel.mtwilson.policy.PolicyEngine;
import com.intel.mtwilson.policy.Rule;
import com.intel.mtwilson.policy.RuleResult;
import com.intel.mtwilson.policy.TrustReport;
import com.intel.mtwilson.policy.fault.PcrEventLogContainsUnexpectedEntries;
import com.intel.mtwilson.policy.fault.PcrEventLogMissingExpectedEntries;
import com.intel.mtwilson.policy.impl.HostTrustPolicyManager;
import com.intel.mtwilson.policy.impl.TrustMarker;
import com.intel.mtwilson.policy.rule.PcrEventLogEqualsExcluding;
import com.intel.mtwilson.policy.rule.PcrEventLogIncludes;
import com.intel.mtwilson.policy.rule.PcrEventLogIntegrity;
import com.intel.mtwilson.policy.rule.PcrMatchesConstant;
import com.intel.mtwilson.saml.TxtHostWithAssetTag;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response.Status;
import org.apache.commons.configuration.Configuration;
import org.joda.time.DateTime;
import org.opensaml.xml.ConfigurationException;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 *
 * @author dsmagadx
 */
public class HostTrustBO {
    public static final String SAML_KEYSTORE_NAME = "SAML";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HostTrustBO.class);
    Marker sysLogMarker = MarkerFactory.getMarker(LogMarkers.HOST_ATTESTATION.getValue());
    
    private static final int DEFAULT_CACHE_VALIDITY_SECS = 3600;
    private static final int CACHE_VALIDITY_SECS;
    private Resource samlKeystoreResource = null;
    private ObjectMapper mapper;

    
    private HostBO hostBO = new HostBO(); 
    
    static{
        CACHE_VALIDITY_SECS = ASConfig.getConfiguration().getInt("saml.validity.seconds", DEFAULT_CACHE_VALIDITY_SECS);
        //log.debug("Config saml.validity.seconds = " + CACHE_VALIDITY_SECS);
    }
    
    public HostTrustBO() {
        super();
        JacksonObjectMapperProvider provider = new JacksonObjectMapperProvider();
        provider = new JacksonObjectMapperProvider();
        mapper = provider.createDefaultMapper();
        loadSamlSigningKey();
    }
    
//    public HostTrustBO(PersistenceManager pm) {
//        super(pm);
//        loadSamlSigningKey();
//    }
    
    public void setHostBO(HostBO hostBO) { this.hostBO = hostBO; }
    
    private void loadSamlSigningKey() {
        /*
        MwKeystore mwKeystore = keystoreJpa.findMwKeystoreByName(SAML_KEYSTORE_NAME);
        if( mwKeystore != null && mwKeystore.getKeystore() != null ) {
            samlKeystoreResource = new ByteArrayResource(mwKeystore.getKeystore());
        }
        */
        //samlKeystoreResource = new FileResource(ResourceFinder.getFile(ASConfig.getConfiguration().getString("saml.keystore.file", "SAML.jks"))); 
        samlKeystoreResource = new FileResource(My.configuration().getSamlKeystoreFile());
    }
        
    /**
     * BUG #607 complete rewrite of this to use the "TrustPolicy" framework in the trust-policy module 
     * instead of the "Strategy" and "IManifest" framework in what was in vmware-trust-utils module.
     * 
     * @param hostName must not be null
     * @return 
     */
    public HostTrustStatus getTrustStatus(Hostname hostName) throws IOException {
        if( hostName == null ) { throw new IllegalArgumentException("missing hostname"); }
//        long start = System.currentTimeMillis();
        
        TblHosts tblHosts = getHostByName(hostName);
        return getTrustStatus(tblHosts, hostName.toString());
    }
    
    public HostTrustStatus getTrustStatusByAik(Sha1Digest aik) throws IOException {
        if( aik == null ) { throw new IllegalArgumentException("missing AIK fingerprint"); }
        try {
            TblHosts tblHosts = getHostByAik(aik);
            return getTrustStatus(tblHosts, aik.toString());
        }
        catch(IOException e) {
            log.error("Cannot get trust status for {}", aik.toString(), e); // log the error for sysadmin to troubleshoot, since we are not allowing the original exception to propagate
            throw new IOException("Cannot get trust status for "+aik.toString()); // rethrowing to make sure that the hostname is not leaked from an exception message; we only provide the AIK in the message
        }
    }
    
    /**
     * This function verifies the host's PCR measurements against the possible MLE whitelists that it could be mapped to
     * and accordingly returns backs the caller the correct MLE name (both BIOS and VMM) using which the host can be 
     * registered.
     * @param hostObj
     * @return
     * @throws IOException 
     */
    public HostResponse getTrustStatusOfHostNotInDBAndRegister(HostConfigData hostConfigObj) {
        if( hostBO == null ) { throw new IllegalStateException("Invalid server configuration"); }
        boolean biosMLEFound = false, VMMMLEFound = false;
        TxtHostRecord hostObj = hostConfigObj.getTxtHostRecord();
        
            // JONATHAN BOOKMARK TODO COMMENT OUT THIS BLOCK  BECAUSE OF CLEAR TEXT PASSWORDS  AFTER DEBUGGING
//            if( log.isDebugEnabled() ) {
//                try {
//                ObjectMapper mapper = new ObjectMapper();
//                log.debug("getTrustStatusOfHostNotInDBAndRegister input: {}", mapper.writeValueAsString(hostConfigObj)); //This statement may contain clear text passwords
//                }
//                catch(IOException e) {
//                    log.debug("cannot serialize host input to addHost", e);
//                }
//            }
            
        
        try {
            
            long getTrustStatusOfHostNotInDBStart = System.currentTimeMillis();
            
            log.debug("getTrustStatusOfHostNotInDB: Starting to find the matching MLEs for host {}.", hostObj.HostName);
            TblMleJpaController mleJpa = My.jpa().mwMle();

            // Create a new TxtHostRecord object which would be used to register the host after we find the matching MLEs
            TxtHostRecord hostObjToRegister = new TxtHostRecord();
            hostObjToRegister.HostName = hostObj.HostName;
            hostObjToRegister.AddOn_Connection_String = hostObj.AddOn_Connection_String;
            if (hostObj.Port != null) { hostObjToRegister.Port = hostObj.Port; }
            hostObjToRegister.tlsPolicyChoice = hostObj.tlsPolicyChoice;
            
            TblHosts tblHosts = new TblHosts();
            tblHosts.setName(hostObj.HostName);
            tblHosts.setAddOnConnectionInfo(hostObj.AddOn_Connection_String);
            tblHosts.setTlsPolicyChoice(hostObj.tlsPolicyChoice);  // either a tls policy id or a tls policy descriptor
            
            tblHosts.setIPAddress(hostObj.HostName);
            if (hostObj.Port != null) {
                tblHosts.setPort(hostObj.Port);
            }
            
            HostAgentFactory factory = new HostAgentFactory();
            HostAgent agent = factory.getHostAgent(tblHosts);
            if( !agent.isTpmEnabled() || !agent.isIntelTxtEnabled() ) {
                throw new ASException(ErrorCode.AS_INTEL_TXT_NOT_ENABLED, hostObj.HostName);
            }

            PcrManifest pcrManifest = agent.getPcrManifest();
            if( pcrManifest == null ) {
                throw new ASException(ErrorCode.AS_HOST_MANIFEST_MISSING_PCRS);
            }

            HostReport hostReport = new HostReport();
            hostReport.pcrManifest = pcrManifest;
            hostReport.tpmQuote = null; 
            hostReport.variables = new HashMap<String,String>(); 
            
            // Bug-1037: aik cert is empty for hostReport
            if (agent.isAikCaAvailable()) {
                hostReport.aik = new Aik(agent.getAikCertificate());
            } else if (agent.isAikAvailable()) {
                hostReport.aik = new Aik(agent.getAik());
            }

            log.debug("getTrustStatusOfHostNotInDB: Successfully retrieved the TPM measurements from host '{}' for identifying the MLE to be mapped to.", hostObj.HostName);
            HostTrustPolicyManager hostTrustPolicyFactory = new HostTrustPolicyManager(My.persistenceManager().getASData());
            
            // First let us find the matching BIOS MLE for the host. This should retrieve all the MLEs with additional
            // numeric extensions if any.
            List<TblMle> biosMLEList;
            if (hostConfigObj.getBiosWLTarget() == null) {

                log.debug("getTrustStatusOfHostNotInDB: Retrieving the list of MLEs with version {} for OEM {} for matching the whitelists.", 
                        hostObj.BIOS_Version, hostObj.BIOS_Oem);
                
                biosMLEList = mleJpa.findBiosMleByVersion(hostObj.BIOS_Version, hostObj.BIOS_Oem);
                
            } else {
                
                String targetType = hostConfigObj.getBiosWLTarget().getValue();
                String targetValue = "";
                switch(hostConfigObj.getBiosWLTarget()) {
                    case BIOS_OEM :
                        targetValue = hostObj.BIOS_Oem;
                        break;
                    case BIOS_HOST :
                        targetValue = hostObj.HostName;
                        break;
                    default :
                        targetValue = "";
                }
                
                log.debug("getTrustStatusOfHostNotInDB: Retrieving the list of MLEs with version {} for OEM {} having target {}-{} for matching the whitelists.", 
                        hostObj.BIOS_Version, hostObj.BIOS_Oem, targetType, targetValue);
                
                biosMLEList = mleJpa.findBiosMleByTarget(hostObj.BIOS_Version, hostObj.BIOS_Oem, targetType, targetValue);

            }

            if (biosMLEList != null && !biosMLEList.isEmpty()) {
                for (TblMle biosMLE : biosMLEList) {
                    log.debug("getTrustStatusOfHostNotInDB: Processing BIOS MLE {} with version {}.", biosMLE.getName(), biosMLE.getVersion());
                    tblHosts.setBiosMleId(biosMLE);

                    Policy trustPolicy = hostTrustPolicyFactory.loadTrustPolicyForMLEVerification(tblHosts, tblHosts.getName()); 
                    PolicyEngine policyEngine = new PolicyEngine();
                    TrustReport trustReport = policyEngine.apply(hostReport, trustPolicy);

                    // Let us update the TxtHostRecord object with the details. We will use that object for host registration later                        
                    hostObjToRegister.BIOS_Name = biosMLE.getName();
                    hostObjToRegister.BIOS_Version = biosMLE.getVersion();
                    hostObjToRegister.BIOS_Oem = biosMLE.getOemId().getName();

                    if (trustReport != null && trustReport.isTrustedForMarker(TrustMarker.BIOS.name())) {
                        // We found the MLE to map to. So, we can continue to process the VMM MLE mapping
                        log.debug("getTrustStatusOfHostNotInDB: BIOS MLE '{}' matches the version on host '{}'.", biosMLE.getName(), hostObj.HostName);
                        biosMLEFound = true;
                        break;
                    } else {
                        // Since there was a mismatch we need to continue looking for additonal MLEs. We still need to track
                        // the BIOS MLE name so that if nothing matches, we assign the host to the last MLE found.
                        log.debug("getTrustStatusOfHostNotInDB: BIOS attestation failed for host '{}' against MLE '{}'.", hostObj.HostName, biosMLE.getName());
                    }
                }
            } else {
                log.error("getTrustStatusOfHostNotInDB: BIOS MLE search for '{}' did not retrieve any matching MLEs.", hostObj.BIOS_Name);
                log.error("getTrustStatusOfHostNotInDB: BIOS MLE {} with version {} is not configured for host {}.", hostObj.BIOS_Name, hostObj.BIOS_Version, hostObj.HostName);
                throw new ASException(ErrorCode.AS_MLE_DOES_NOT_EXIST, hostObj.BIOS_Name, hostObj.BIOS_Version);
            }

            // If at the end of the above loop, we do not find any BIOS MLE matching for the host we need to throw an appropriate exception.
            // Note that the MLE can make either the host trusted or un-trusted. As long as we have an MLE with matching name and version, 
            // we need to map the host to it even though it might make the host untrusted.
            if ((hostObjToRegister.BIOS_Name == null) || (hostObjToRegister.BIOS_Name.isEmpty())) {
                log.error("getTrustStatusOfHostNotInDB: BIOS MLE {} with version {} is not configured for host {}.", hostObj.BIOS_Name, hostObj.BIOS_Version, hostObj.HostName);
                throw new ASException(ErrorCode.AS_MLE_DOES_NOT_EXIST, hostObj.BIOS_Name, hostObj.BIOS_Version);
            }
            
            if (!biosMLEFound) {
                log.info("getTrustStatusOfHostNotInDB: No matching BIOS MLE found for {}. So mapping it to the last found MLE '{}' matching the name and version.", 
                        hostObj.HostName, hostObjToRegister.BIOS_Name);
            }
            
            // Reset the BIOS MLE ID so that we don't verify that again along with VMM MLE
            tblHosts.setBiosMleId(null);
            
            // First let us find the matching VMM MLEs for the host that is configured in the system.
            //List<TblMle> vmmMLEList = mleJpa.findVMMMLEByNameSearchCriteria(hostObj.VMM_Name);
            List<TblMle> vmmMLEList; 
            if (hostConfigObj.getVmmWLTarget() == null) {
                
                vmmMLEList = mleJpa.findVmmMleByVersion(hostObj.VMM_Version, hostObj.VMM_OSName, hostObj.VMM_OSVersion);
                
            } else {
                String targetType = hostConfigObj.getVmmWLTarget().getValue();
                String targetValue = "";
                switch(hostConfigObj.getVmmWLTarget()) {
                    case VMM_OEM :
                        targetValue = hostObj.BIOS_Oem;
                        break;
                    case VMM_HOST :
                        targetValue = hostObj.HostName;
                        break;
                    case VMM_GLOBAL :
                    default :
                        targetValue = "";
                }

                log.debug("getTrustStatusOfHostNotInDB: Retrieving the list of MLEs with VMM version {} for OS {} - {} having target {} - {} for matching the whitelists.", 
                        hostObj.VMM_Version, hostObj.VMM_OSName, hostObj.VMM_OSVersion, targetType, targetValue);
                
                vmmMLEList = mleJpa.findVmmMleByTarget(hostObj.VMM_Version, hostObj.VMM_OSName, hostObj.VMM_OSVersion, targetType, targetValue);
                
            }
            if (vmmMLEList != null && !vmmMLEList.isEmpty()) {
                for (TblMle vmmMLE : vmmMLEList) {
                    
                    log.debug("getTrustStatusOfHostNotInDB: Processing VMM MLE {} with version {}.", vmmMLE.getName(), vmmMLE.getVersion());                    
                    
                    tblHosts.setVmmMleId(vmmMLE);

                    Policy trustPolicy = hostTrustPolicyFactory.loadTrustPolicyForMLEVerification(tblHosts, tblHosts.getName()); 
                    PolicyEngine policyEngine = new PolicyEngine();
                    TrustReport trustReport = policyEngine.apply(hostReport, trustPolicy);

                    // Let us update the TxtHostRecord object with the details. We will use it for host registration later                        
                    hostObjToRegister.VMM_Name = vmmMLE.getName();
                    hostObjToRegister.VMM_Version = vmmMLE.getVersion();
                    hostObjToRegister.VMM_OSName = vmmMLE.getOsId().getName();
                    hostObjToRegister.VMM_OSVersion = vmmMLE.getOsId().getVersion();

                    if (trustReport != null && trustReport.isTrustedForMarker(TrustMarker.VMM.name())) {
                        // We found the MLE to map to. 
                        log.debug("getTrustStatusOfHostNotInDB: VMM MLE '{}' matches the version on host '{}'.", vmmMLE.getName(), hostObj.HostName);
                        VMMMLEFound = true;
                        break;
                    } else {
                        // Since there was a mismatch we need to continue looking for additonal MLEs. We still need to track
                        // the BIOS MLE name so that if nothing matches, we assign the host to the last MLE found.
                        log.debug("getTrustStatusOfHostNotInDB: VMM attestation failed for host '{}' against MLE '{}'.", hostObj.HostName, vmmMLE.getName());
                    }                        
                }
            } else {
                log.error("getTrustStatusOfHostNotInDB: VMM MLE search for '{}' did not retrieve any matching MLEs.", hostObj.VMM_Name);
                log.error("getTrustStatusOfHostNotInDB: VMM MLE {} with version {} is not configured for host {}.", hostObj.VMM_Name, hostObj.VMM_Version, hostObj.HostName);
                log.error(String.format(ErrorCode.AS_MLE_DOES_NOT_EXIST.getMessage(), hostObj.VMM_Name, hostObj.VMM_Version));
                throw new ASException(ErrorCode.AS_MLE_DOES_NOT_EXIST, hostObj.VMM_Name, hostObj.VMM_Version);
            }
            
            // If at the end of the above loop, we do not find any VMM MLE matching for the host we need to throw an appropriate exception
            if ((hostObjToRegister.VMM_Name == null) || (hostObjToRegister.VMM_Name.isEmpty())) {
                log.error("VMM MLE {} with version {} is not configured for host {}.", hostObj.VMM_Name, hostObj.VMM_Version, hostObj.HostName);
                throw new ASException(ErrorCode.AS_MLE_DOES_NOT_EXIST, hostObj.VMM_Name, hostObj.VMM_Version);
            }

            if (!VMMMLEFound) {
                log.info("getTrustStatusOfHostNotInDB: No matching VMM MLE found for {}. So mapping it to the last found MLE '{}' matching the name and version.", 
                        hostObj.HostName, hostObjToRegister.BIOS_Name);
            }

            HostResponse hostResponse;
            
            // We need to check if the host is already configured in the system. If yes, we need to update the host or else create a new one
            if (hostBO.getHostByName(new Hostname((hostObj.HostName))) != null) {
                // update the host
                hostResponse = hostBO.updateHost(new TxtHost(hostObjToRegister), pcrManifest, agent, null);
            } else {
                // create the host
                hostResponse = hostBO.addHost(new TxtHost(hostObjToRegister), pcrManifest, agent, null);
            }
                
            long getTrustStatusOfHostNotInDBStop = System.currentTimeMillis();
            log.debug("GetTrustStatusOfHostNotInDB performance overhead is {} milliseconds", (getTrustStatusOfHostNotInDBStop - getTrustStatusOfHostNotInDBStart));            
            
            return hostResponse;
            
        } catch (ASException ae) {
            throw ae;
                        
        } catch (Exception ex) {
            // If in case we get any exception, we just return back the default names so that we don't end up with aborting the complete operation.            
            log.error("Error during host registration/update.", ex);
            throw new ASException(ErrorCode.AS_REGISTER_HOST_ERROR, ex.getClass().getSimpleName());
        }        
    }
    
    /**
     * This function verifies the host's PCR measurements against the possible MLE whitelists that it could be mapped to
     * and accordingly returns backs the caller the correct MLE name (both BIOS and VMM) using which the host can be 
     * registered.
     * @param hostObj
     * @return
     * @throws IOException 
     */
    public TrustReport updateHostIfUntrusted(TblHosts tblHosts, HostReport hostReport, TrustReport trustReport, HostAgent agent) {
        if( hostBO == null ) { throw new IllegalStateException("Invalid server configuration"); }
        String regExForNumericExtNames = ".*_([^_][0-9]*)$"; // Regular expression to match the host names with numeric extenstions
        boolean updateBIOSMLE = false, updateVMMMLE = false;
        
        try {
            
            long updateHostIfUntrustedStart = System.currentTimeMillis();
            
            log.debug("UpdateHostIfUntrusted: Checking to see if the host needs to be mapped to a different MLE in case of untrusted status of either BIOS or VMM");
            
            // Check the configuration in the property file to see if we need to update the host or not
            if (!My.configuration().getAutoUpdateHosts()) {
                log.info("UpdateHostIfUntrusted: Skipping the auto host update as per the configuration in the mtwilson.properties file.");
                return trustReport;
            }
            
            if (trustReport.isTrustedForMarker(TrustMarker.BIOS.name()) && trustReport.isTrustedForMarker(TrustMarker.VMM.name())) {
                // since both BIOS and VMM are trusted, we do not need to update the host.
                log.debug("UpdateHostIfUntrusted: Since the host is trusted, there is no need to update the host to map to other MLEs.");
                return trustReport;
            }                            

            HostTrustPolicyManager hostTrustPolicyFactory = new HostTrustPolicyManager(My.persistenceManager().getASData());
            TblMleJpaController mleJpa = My.jpa().mwMle();
            TblMle currentBIOSMLE = tblHosts.getBiosMleId();
            TblMle currentVMMMLE = tblHosts.getVmmMleId();
            Integer hostID = tblHosts.getId();
            TblMle newBIOSMLE = new TblMle();
            TblMle newVMMMLE = new TblMle();
            
            // Since either BIOS or VMM or both are untrusted, we need to check if the host has been updated and would match any other
            // MLE already configured in the system. If yes, then we need to update the host to map to the new MLE. Otherwise the host
            // will not be updated
            
            // First create a TxtHostRecord object holder to hold the names of the MLEs that would be used to update the host if needed
            TxtHostRecord hostUpdateObj = new TxtHostRecord();
            hostUpdateObj.HostName = tblHosts.getName();
            hostUpdateObj.AddOn_Connection_String = tblHosts.getAddOnConnectionInfo();
            
            long updateHostIfUntrustedBIOSStart = System.currentTimeMillis();
            // Find a new matching MLE is the host becomes untrusted because of BIOS MLE
            if (!trustReport.isTrustedForMarker(TrustMarker.BIOS.name())) {
                
                // BIOS has become untrusted. So, we need to check if there is any other BIOS MLE that matches the host. If so, then we
                // use that MLE or else we will keep the same one even though the BIOS is untrusted.
                log.debug("UpdateHostIfUntrusted: Starting to find the matching BIOS MLE.");
                
                // In order to look for other MLE names, we need to search on the default MLE name without extensions so that when the 
                // search results are returned all the MLEs with numeric extensions are also retrieved.
                // Ex: If the current BIOS MLE is Intel_Corp_002, and if we just do a search using the complete name, it will not
                // return back any other existing MLEs. So, we ned to remove "_002" and then do a search
                String biosName = tblHosts.getBiosMleId().getName();
                if (biosName.matches(regExForNumericExtNames)) {
                    biosName = biosName.substring(0, (biosName.lastIndexOf("_")));
                } else {
                    // host is already mapped to the default MLE. So, no need for any further name manipulation
                }

                log.debug("UpdateHostIfUntrusted: Search criteria for BIOS MLE name is {}.", biosName);
                
                List<TblMle> biosMLEList = mleJpa.findBIOSMLEByNameSearchCriteria(biosName);
                if (biosMLEList != null && !biosMLEList.isEmpty()) {
                    for (TblMle biosMLE : biosMLEList) {
                        
                        log.debug("UpdateHostIfUntrusted: Checking BIOS MLE {} - {} for the match of whitelist.", biosMLE.getName(), biosMLE.getVersion());
                        
                        // Skip the MLE which is currently assigned to the host
                        if ((tblHosts.getBiosMleId().getName().equalsIgnoreCase(biosMLE.getName())) && 
                                (tblHosts.getBiosMleId().getVersion().equalsIgnoreCase(biosMLE.getVersion()))) {
                            continue;
                        }
                        
                        // We need to set the VMM MLE ID to null so that we won't verify the VMM policy now. We will do
                        // it later.
                        tblHosts.setBiosMleId(biosMLE);
                        tblHosts.setVmmMleId(null);

                        Policy trustPolicy = hostTrustPolicyFactory.loadTrustPolicyForHost(tblHosts, tblHosts.getName()); 
                        PolicyEngine policyEngine = new PolicyEngine();
                        TrustReport tempTrustReport = policyEngine.apply(hostReport, trustPolicy);

                        if (tempTrustReport != null && tempTrustReport.isTrustedForMarker(TrustMarker.BIOS.name())) {
                            // We found the new MLE to map to. We need to see if the VMM MLE also needs to be updated
                            log.debug("UpdateHostIfUntrusted: Found the new matching BIOS MLE '{}' for host '{}'.", biosMLE.getName(), tblHosts.getName());
                            
                            // Update the TXTHostRecord object with the MLE details
                            hostUpdateObj.BIOS_Name = biosMLE.getName();
                            hostUpdateObj.BIOS_Version = biosMLE.getVersion();
                            hostUpdateObj.BIOS_Oem = biosMLE.getOemId().getName();
                            
                            updateBIOSMLE = true;
                            newBIOSMLE = biosMLE;
                            
                            break;
                        } else {
                            // Since there was a mismatch we need to continue looking for additonal MLEs. 
                            log.debug("UpdateHostIfUntrusted: BIOS attestation failed for host '{}' against MLE '{}'.", tblHosts.getName(), biosMLE.getName());
                        }
                    }
                } 
            }
            
            log.debug("UpdateHostIfUntrusted: Status of BIOS MLE update is {}", updateBIOSMLE);
            
            // if either the BIOS was trusted to start with or we did not find another BIOS MLE to map to, we store the current BIOS MLE
            // value in the TxtHostRecord object.
            if (trustReport.isTrustedForMarker(TrustMarker.BIOS.name()) || updateBIOSMLE == false) {
                // Since the BIOS is trusted, we need not update the BIOS. So, just store the existing BIOS details in the TxtHostRecord Object
                hostUpdateObj.BIOS_Name = currentBIOSMLE.getName();
                hostUpdateObj.BIOS_Version = currentBIOSMLE.getVersion();
                hostUpdateObj.BIOS_Oem = currentBIOSMLE.getOemId().getName();               
            } 
            
            long updateHostIfUntrustedBIOSStop = System.currentTimeMillis();
            log.debug("UpdateHostIfUntrusted BIOS update performance {}", (updateHostIfUntrustedBIOSStop - updateHostIfUntrustedBIOSStart));
            
            // Reset the MLE IDs back in the TblHosts object
            tblHosts.setBiosMleId(currentBIOSMLE);
            tblHosts.setVmmMleId(currentVMMMLE);
            
            long updateHostIfUntrustedVMMStart = System.currentTimeMillis();
            // Find a new matching MLE is the host becomes untrusted because of VMM MLE
            if (!trustReport.isTrustedForMarker(TrustMarker.VMM.name())) {

                // VMM has become untrusted. So, we need to check if there is any other VMM MLE that matches the host. If so, then we
                // use that MLE or else we will keep the same one even though the VMM is untrusted.
                log.debug("UpdateHostIfUntrusted: Starting to find the matching VMM MLE.");
                
                // Since we want to skip doing the host specific module attestation we will set the tblHosts ID to null
                tblHosts.setId(null);
                
                // In order to look for other MLE names, we need to search on the default MLE name without extensions so that when the 
                // search results are returned all the MLEs with numeric extensions are also retrieved.
                String vmmName = tblHosts.getVmmMleId().getName();
                if (vmmName.matches(regExForNumericExtNames)) {
                    vmmName = vmmName.substring(0, (vmmName.lastIndexOf("_")));
                } else {
                    // host is already mapped to the default MLE. So, no need for any further name manipulation
                }

                log.debug("UpdateHostIfUntrusted: Search criteria for VMM MLE name is {}.", vmmName);
                
                List<TblMle> vmmMLEList = mleJpa.findVMMMLEByNameSearchCriteria(vmmName);
                if (vmmMLEList != null && !vmmMLEList.isEmpty()) {
                    for (TblMle vmmMLE : vmmMLEList) {
                        
                        log.debug("UpdateHostIfUntrusted: Checking VMM MLE {} - {} for the match of whitelist.", vmmMLE.getName(), vmmMLE.getVersion());
                        
                        // Skip the MLE which is currently assigned to the host
                        if ((tblHosts.getVmmMleId().getName().equalsIgnoreCase(vmmMLE.getName())) && 
                                (tblHosts.getVmmMleId().getVersion().equalsIgnoreCase(vmmMLE.getVersion()))) {
                            continue;
                        }
                        
                        // We need to set the BIOS MLE ID to null so that we won't verify the BIOS policy again.
                        tblHosts.setBiosMleId(null);
                        tblHosts.setVmmMleId(vmmMLE);

                        Policy trustPolicy = hostTrustPolicyFactory.loadTrustPolicyForMLEVerification(tblHosts, tblHosts.getName()); 
                        PolicyEngine policyEngine = new PolicyEngine();
                        TrustReport tempTrustReport = policyEngine.apply(hostReport, trustPolicy);

                        if (tempTrustReport != null && tempTrustReport.isTrustedForMarker(TrustMarker.VMM.name())) {
                            // We found the new VMM MLE to map to. 
                            log.debug("UpdateHostIfUntrusted: Found the new matching VMM MLE '{}' for host '{}'.", vmmMLE.getName(), tblHosts.getName());
                            
                            // Update the TXTHostRecord object with the MLE details
                            hostUpdateObj.VMM_Name = vmmMLE.getName();
                            hostUpdateObj.VMM_Version = vmmMLE.getVersion();
                            hostUpdateObj.VMM_OSName = vmmMLE.getOsId().getName();
                            hostUpdateObj.VMM_OSVersion = vmmMLE.getOsId().getVersion();
                            
                            updateVMMMLE = true;
                            newVMMMLE = vmmMLE;
                            
                            break;
                        } else {
                            // Since there was a mismatch we need to continue looking for additonal MLEs. 
                            log.debug("UpdateHostIfUntrusted: VMM attestation failed for host '{}' against MLE '{}'.", tblHosts.getName(), vmmMLE.getName());
                        }
                    }
                } 
            }
            
            log.debug("UpdateHostIfUntrusted: Status of VMM MLE update is {}", updateVMMMLE);
            
            // if either the VMM was trusted to start with or we did not find another VMM MLE to map to, we store the current VMM MLE
            // value in the TxtHostRecord object.
            if (trustReport.isTrustedForMarker(TrustMarker.VMM.name()) || updateVMMMLE == false) {
                // Since the BIOS is trusted, we need not update the BIOS. So, just store the existing BIOS details in the TxtHostRecord Object
                hostUpdateObj.VMM_Name = currentVMMMLE.getName();
                hostUpdateObj.VMM_Version = currentVMMMLE.getVersion();
                hostUpdateObj.VMM_OSName = currentVMMMLE.getOsId().getName();
                hostUpdateObj.VMM_OSVersion = currentVMMMLE.getOsId().getVersion();                
            } 

            long updateHostIfUntrustedVMMStop = System.currentTimeMillis();
            log.debug("UpdateHostIfUntrusted: VMM update performance {}", (updateHostIfUntrustedVMMStop - updateHostIfUntrustedVMMStart));
            
            // We need to update the host only if we found a new BIOS MLE or a VMM MLE to map to the host so that host would be trusted
            if (updateBIOSMLE || updateVMMMLE) {
                hostBO.updateHost(new TxtHost(hostUpdateObj), hostReport.pcrManifest, agent, null);
            }

            if (updateBIOSMLE)
                tblHosts.setBiosMleId(newBIOSMLE);
            else
                tblHosts.setBiosMleId(currentBIOSMLE);
            
            if (updateVMMMLE)
                tblHosts.setVmmMleId(newVMMMLE);
            else
                tblHosts.setVmmMleId(currentVMMMLE);

            tblHosts.setId(hostID);
            Policy trustPolicy = hostTrustPolicyFactory.loadTrustPolicyForHost(tblHosts, tblHosts.getName()); 
            PolicyEngine policyEngine = new PolicyEngine();
            TrustReport finalTrustReport = policyEngine.apply(hostReport, trustPolicy);            
            
            long updateHostIfUntrustedStop = System.currentTimeMillis();
            log.debug("UpdateHostIfUntrusted Performance {}", (updateHostIfUntrustedStop - updateHostIfUntrustedStart));
            
            return finalTrustReport;
                                   
        } catch (ASException ae) {
            throw ae;
            
        } catch (IOException ioex) {
            // If in case we get any exception, we just return back the default names so that we don't end up with aborting the complete operation.            
            log.error("IO Exception during automatic host update since the host became untrusted. {}.", ioex.getMessage());
            return trustReport;
            
        } catch (Exception ex) {
            // If in case we get any exception, we just return back the default names so that we don't end up with aborting the complete operation.            
            log.error("Error during automatic host update since the host became untrusted. {}.", ex.getMessage());
            return trustReport;
        }           
    }
    
    
    /**
     * 
     * @param tblHosts
     * @param hostId can be Hostname or AIK (SHA1 hex) ; it's used in any exceptions to refer to the host.  this allows us to use the same code for a trust report lookup by hostname and by aik
     * @return 
     */
    public HostTrustStatus getTrustStatus(TblHosts tblHosts, String hostId) throws IOException {
        if (tblHosts == null) {
            throw new ASException(
                    ErrorCode.AS_HOST_NOT_FOUND,
                    hostId);
        }
        long start = System.currentTimeMillis();
        log.debug( "VMM name for host is {}", tblHosts.getVmmMleId().getName());
        log.debug( "OS name for host is {}", tblHosts.getVmmMleId().getOsId().getName());

        TrustReport trustReport = getTrustReportForHost(tblHosts, hostId);
        List<RuleResult> results = trustReport.getResults();
        for (RuleResult res : results) {
            log.debug("Trust Report Rule Name: {}", res.getRuleName());
            if (!res.isTrusted()) {
                for (Fault f : res.getFaults()) {
                    if (f != null && f.getFaultName() != null && f.getCause() != null)
                        log.debug("Trust report|Fault Name: {} | Fault Cause: {}", f.getFaultName(), f.getCause().getMessage());
                }
            }
        }
        if( trustReport.getHostReport() == null || trustReport.getHostReport().pcrManifest == null ) {
            throw new ASException(ErrorCode.AS_HOST_MANIFEST_MISSING_PCRS);
        }
        
        
        HostTrustStatus trust = new HostTrustStatus();
        trust.bios = trustReport.isTrustedForMarker(TrustMarker.BIOS.name());
        trust.vmm = trustReport.isTrustedForMarker(TrustMarker.VMM.name());

        // previous check for trusted location was if the host's location field is not null, then it's trusted... but i think this is better as it checks the pcr.  
        
        // Going ahead we will not be using location. It would be replaced by asset_tag. Location can be one of the asset tags.
        //trust.location = trustReport.isTrustedForMarker(TrustMarker.LOCATION.name());
        trust.asset_tag = trustReport.isTrustedForMarker(TrustMarker.ASSET_TAG.name());
        
        Date today = new Date(System.currentTimeMillis()); // create the date here and pass it down, in order to ensure that all created records use the same timestamp
        logOverallTrustStatus(tblHosts, trust, today);
        logPcrTrustStatus(tblHosts, trustReport, today);
        

        String userName = new AuditLogger().getAuditUserName();
        Object[] paramArray = {userName, hostId, trust.bios, trust.vmm, trust.asset_tag};
        log.info(sysLogMarker, "User_Name: {} Host_Name: {} BIOS_Trust: {} VMM_Trust: {} AT_Trust: {}.", paramArray);
        
        log.debug( "Verfication Time {}", (System.currentTimeMillis() - start));

        return trust;
    }
    
    public void logTrustReport(TblHosts tblHosts, TrustReport trustReport) {

        if( trustReport.getHostReport() == null || trustReport.getHostReport().pcrManifest == null ) {
            throw new ASException(ErrorCode.AS_HOST_MANIFEST_MISSING_PCRS);
        }
        
        HostTrustStatus trust = new HostTrustStatus();
        trust.bios = trustReport.isTrustedForMarker(TrustMarker.BIOS.name());
        trust.vmm = trustReport.isTrustedForMarker(TrustMarker.VMM.name());
        trust.asset_tag = trustReport.isTrustedForMarker(TrustMarker.ASSET_TAG.name());
        
        Date today = new Date(System.currentTimeMillis()); // create the date here and pass it down, in order to ensure that all created records use the same timestamp
        logOverallTrustStatus(tblHosts, trust, today);
        logPcrTrustStatus(tblHosts, trustReport, today);
        
    }
    
    /**
     * NOTE:  the trust report MUST NOT include the host name or ip address;  it's fine to include the AIK.
     * This property allows the trust report to be used anonymously or to be attached to hostname/ipaddress 
     * at a higher level if needed for a non-privacy application.
     * 
     * @param tblHosts
     * @return
     * @throws IOException 
     */
    public TrustReport getTrustReportForHost(TblHosts tblHosts, String hostId) throws IOException {
        // bug #538 first check if the host supports tpm
        HostAgentFactory factory = new HostAgentFactory();
        long getAgentStart = System.currentTimeMillis(); 
        HostAgent agent = factory.getHostAgent(tblHosts);
        long getAgentStop = System.currentTimeMillis();
        log.trace("performance: getHostAgent: {}ms", getAgentStop-getAgentStart);
        if( !agent.isTpmEnabled() || !agent.isIntelTxtEnabled() ) {
            throw new ASException(ErrorCode.AS_INTEL_TXT_NOT_ENABLED, hostId);
        }
        
        long getAgentManifestStart = System.currentTimeMillis(); 
        PcrManifest pcrManifest = agent.getPcrManifest();
        long getAgentManifestStop = System.currentTimeMillis();
        log.trace("performance: getPcrManifest: {}ms", getAgentManifestStop-getAgentManifestStart);
        
        HostReport hostReport = new HostReport();
        hostReport.tagCertificate = null; 
        hostReport.pcrManifest = pcrManifest;
        hostReport.tpmQuote = null; 
        hostReport.variables = new HashMap<String,String>(); 
        if( agent.isAikAvailable() ) {
            if( agent.isAikCaAvailable() ) {
                hostReport.aik = new Aik(agent.getAikCertificate());                
            }
            else {
                hostReport.aik = new Aik(agent.getAik()); 
            }
        }
        
        HostTrustPolicyManager hostTrustPolicyFactory = new HostTrustPolicyManager(My.persistenceManager().getASData());

        try {
            log.debug("Checking if there are any asset tag certificates mapped to host with ID : {}", tblHosts.getId());
            // Load the asset tag certificate only if it is associated and valid.
            AssetTagCertBO atagCertBO = new AssetTagCertBO();
            MwAssetTagCertificate atagCertForHost = atagCertBO.findValidAssetTagCertForHost(tblHosts.getId());            
            if (atagCertForHost != null) {
                log.debug("Asset tag certificate is associated to host {} with status {}.", tblHosts.getName(), atagCertForHost.getRevoked());
                hostReport.tagCertificate = X509AttributeCertificate.valueOf(atagCertForHost.getCertificate());
            }
            else {
                log.debug("Asset tag certificate is either not associated or valid for host {}.", tblHosts.getName());
            }
        } catch (Exception ex) {
            log.error("Exception when looking up the asset tag whitelist.", ex);
            // We cannot do anything ... just log the error and proceed
            log.info("Error during look up of asset tag certificates for the host {}", tblHosts.getName());
        }
        
        
        long getTrustPolicyStart = System.currentTimeMillis();
        Policy trustPolicy = hostTrustPolicyFactory.loadTrustPolicyForHost(tblHosts, hostId); // must include both bios and vmm policies
        long getTrustPolicyStop = System.currentTimeMillis();
        log.trace("performance: loadTrustPolicyForHost: {}ms", getTrustPolicyStop-getTrustPolicyStart);
//        trustPolicy.setName(policy for hostId) // do we even need a name? or is that just a management thing for the app?
        PolicyEngine policyEngine = new PolicyEngine();
        long applyPolicyStart = System.currentTimeMillis();
        TrustReport trustReport = policyEngine.apply(hostReport, trustPolicy);
        long applyPolicyStop = System.currentTimeMillis();
        log.trace("performance: policyEngine.apply: {}ms", applyPolicyStop-applyPolicyStart);
        
        if (!trustReport.isTrustedForMarker(TrustMarker.BIOS.name()) || !trustReport.isTrustedForMarker(TrustMarker.VMM.name())) {
            trustReport = updateHostIfUntrusted(tblHosts, hostReport, trustReport, agent);
        }
        
        return trustReport;
    }

    /**
     * 
     * @param hostName must not be null
     * @param tblSamlAssertion must not be null
     * @return 
     */
    public TxtHost getHostWithTrust(TblHosts tblHosts, String hostId, TblSamlAssertion tblSamlAssertion) throws IOException {
        HostTrustStatus trust = getTrustStatus(tblHosts, hostId);
        TxtHostRecord data = createTxtHostRecord(tblHosts);
        TxtHost host = new TxtHost(data, trust);
        tblSamlAssertion.setHostId(tblHosts);
        return host;
    }

    protected TxtHostRecord createTxtHostRecord(TblHosts from) {
        TxtHostRecord to = new TxtHostRecord();
        to.AddOn_Connection_String = from.getAddOnConnectionInfo();
        to.BIOS_Name = from.getBiosMleId().getName();
        to.BIOS_Version = from.getBiosMleId().getVersion();
        to.BIOS_Oem = from.getBiosMleId().getOemId().getName();
        to.Description = from.getDescription();
        to.Email = from.getEmail();
        to.HostName = from.getName();
        to.IPAddress = from.getName();
        to.Location = from.getLocation();
        to.Port = from.getPort();
        to.VMM_Name = from.getVmmMleId().getName();
        to.VMM_Version = from.getVmmMleId().getVersion();
        to.VMM_OSName = from.getVmmMleId().getOsId().getName();
        to.VMM_OSVersion = from.getVmmMleId().getOsId().getVersion();
        to.AIK_Certificate = from.getAIKCertificate();
        to.AIK_PublicKey = from.getAikPublicKey();
        to.AIK_SHA1 = from.getAikSha1();
        return to;
    }

    /**
     * Gets the host trust status from trust agent
     *
     * @param hostName must not be null
     * @return {@link String}
     */
    public String getTrustStatusString(Hostname hostName) throws IOException { // datatype.Hostname

        HostTrustStatus trust = getTrustStatus(hostName);

        String response = toString(trust);

        log.debug("Overall trust status " + response);

        return response;
    }

    
    private String toString(HostTrustStatus trust) {
        return String.format("BIOS:%d,VMM:%d", (trust.bios) ? 1 : 0,
                (trust.vmm) ? 1 : 0);
    }
/*
    private boolean verifyTrust(TblHosts host, TblMle mle,
            HashMap<String, ? extends IManifest> pcrManifestMap,
            HashMap<String, ? extends IManifest> gkvPcrManifestMap) {
        boolean response = true;

        if (gkvPcrManifestMap.size() <= 0) {
            throw new ASException(ErrorCode.AS_MISSING_MANIFEST, mle.getName(),
                    mle.getVersion());
        }

        for (String pcr : gkvPcrManifestMap.keySet()) {
            if (pcrManifestMap.containsKey(pcr)) {
                IManifest pcrMf = pcrManifestMap.get(pcr);
                boolean trustStatus = pcrMf.verify(gkvPcrManifestMap.get(pcr));
                log.info(String.format("PCR %s Host Trust status %s", pcr,
                        String.valueOf(trustStatus)));

*               logTrustStatus(host, mle,  pcrMf);

                if (!trustStatus) {
                    response = false;
                }

            } else {
                log.info(String.format("PCR %s not found in manifest.", pcr));
                throw new ASException(ErrorCode.AS_PCR_NOT_FOUND,pcr);
            }
        }

        return response;
    }
*/
    /*
    private void logTrustStatus(TblHosts host, TblMle mle, IManifest manifest) {
        Date today = new Date(System.currentTimeMillis());
        PcrManifest pcrManifest = (PcrManifest)manifest;
        
        TblTaLog taLog = new TblTaLog();
        taLog.setHostID(host.getId());
        taLog.setMleId(mle.getId());
        taLog.setManifestName(String.valueOf(pcrManifest.getPcrNumber()));
        taLog.setManifestValue(pcrManifest.getPcrValue());
        taLog.setTrustStatus(pcrManifest.getVerifyStatus());
        taLog.setUpdatedOn(today);

        new TblTaLogJpaController(getEntityManagerFactory()).create(taLog);
        
        if(manifest instanceof PcrModuleManifest){
            saveModuleManifestLog((PcrModuleManifest) manifest,taLog);
        }

    }
    * */
    private void logOverallTrustStatus(TblHosts host, HostTrustStatus status, Date today) {
        try {
            TblTaLog taLog = new TblTaLog();
            taLog.setHostID(host.getId());
            taLog.setMleId(0);
            taLog.setTrustStatus(status.bios && status.vmm); 
            taLog.setError(toString(status));
            taLog.setManifestName(" ");
            taLog.setManifestValue(" ");
            taLog.setHost_uuid_hex(host.getUuid_hex());
            taLog.setUuid_hex(new UUID().toString());
            taLog.setUpdatedOn(today);

            TblTaLogJpaController talog = My.jpa().mwTaLog();
            
            talog.create(taLog); // overall status
    /*        
            // bios
            TblTaLog taLogBios = new TblTaLog();
            taLogBios.setHostID(host.getId());
            taLogBios.setMleId(host.getBiosMleId().getId());
            taLogBios.setTrustStatus(status.bios); 
            taLogBios.setError(toString(status));
            taLogBios.setManifestName(" "); 
            taLogBios.setManifestValue(" ");
            taLogBios.setUpdatedOn(today);
            talog.create(taLogBios);
            
            TblTaLog taLogVmm = new TblTaLog();
            taLogVmm.setHostID(host.getId());
            taLogVmm.setMleId(host.getVmmMleId().getId());
            taLogVmm.setTrustStatus(status.vmm); 
            taLogVmm.setError(toString(status));
            taLogVmm.setManifestName(" ");
            taLogVmm.setManifestValue(" ");
            taLogVmm.setUpdatedOn(today);
            talog.create(taLogVmm);
            */
        } catch (IOException ex) {
            log.error("Error during logging of the overall trust status", ex);
            throw new ASException(ErrorCode.SYSTEM_ERROR, ex.getClass().getSimpleName());
        }
    }
    
    /**
     * Searches for all the PcrMatchesConstant policies in the TrustReport and creates 
     * an entry for each one in the mw_ta_log table... the contents of that table are used
     * to create the "trust report" in the Trust Dashboard 
     * 
     * @param host
     * @param report 
     */
    private void logPcrTrustStatus(TblHosts host, TrustReport report, Date today) {
        try {
            TblTaLogJpaController talogJpa = My.jpa().mwTaLog();
            TblModuleManifestLogJpaController moduleLogJpa = My.jpa().mwModuleManifestLog();
            List<String> biosPcrList = Arrays.asList(host.getBiosMleId().getRequiredManifestList().split(","));
            List<String> vmmPcrList = Arrays.asList(host.getVmmMleId().getRequiredManifestList().split(","));
            List<RuleResult> results = report.getResults();
            log.debug("Found {} results", results.size());
            // we log at most ONE record per PCR ... so keep track here in case multiple rules refer to the same PCR... so we only record it once... hopefully there is no overlap between bios and vmm pcr's!
            HashMap<PcrIndex,TblTaLog> taLogMap = new HashMap<PcrIndex,TblTaLog>();
            for(String biosPcrIndex : biosPcrList) {
                TblTaLog pcr = new TblTaLog();
                pcr.setHostID(host.getId());
                pcr.setMleId(host.getBiosMleId().getId());
                pcr.setHost_uuid_hex(host.getUuid_hex());
                pcr.setUuid_hex(new UUID().toString());
                pcr.setUpdatedOn(today);
                pcr.setTrustStatus(true); // start as true, later we'll change to false if there are any faults 
                pcr.setManifestName(biosPcrIndex);
                if( report.getHostReport().pcrManifest == null || report.getHostReport().pcrManifest.getPcr(Integer.valueOf(biosPcrIndex)) == null ) {
                    throw new ASException(ErrorCode.AS_HOST_MANIFEST_MISSING_PCRS); // will cause the host to show up as "unknown" since there will not be any ta log records
                }
                pcr.setManifestValue(report.getHostReport().pcrManifest.getPcr(Integer.valueOf(biosPcrIndex)).getValue().toString());
                taLogMap.put(PcrIndex.valueOf(Integer.valueOf(biosPcrIndex)), pcr);
            }
            for(String vmmPcrIndex : vmmPcrList) {
                TblTaLog pcr = new TblTaLog();
                pcr.setHostID(host.getId());
                pcr.setMleId(host.getVmmMleId().getId());
                pcr.setHost_uuid_hex(host.getUuid_hex());
                pcr.setUuid_hex(new UUID().toString());
                pcr.setUpdatedOn(today);
                pcr.setTrustStatus(true); // start as true, later we'll change to false if there are any faults 
                pcr.setManifestName(vmmPcrIndex);
                if( report.getHostReport().pcrManifest == null || report.getHostReport().pcrManifest.getPcr(Integer.valueOf(vmmPcrIndex)) == null ) {
                    throw new ASException(ErrorCode.AS_HOST_MANIFEST_MISSING_PCRS); // will cause the host to show up as "unknown" since there will not be any ta log records
                }
                pcr.setManifestValue(report.getHostReport().pcrManifest.getPcr(Integer.valueOf(vmmPcrIndex)).getValue().toString());
                taLogMap.put(PcrIndex.valueOf(Integer.valueOf(vmmPcrIndex)), pcr);
            }
            // Here duplicate the for loop and add in pcr 22 from trustReport
            // check if host has asset tag, then add 
            for(RuleResult result : results) {
                log.debug("Looking at policy {}", result.getRuleName());
                Rule rule = result.getRule();
                if( rule instanceof PcrMatchesConstant ) {
                    PcrMatchesConstant pcrPolicy = (PcrMatchesConstant)rule;
                    log.debug("Expected PCR {} = {}", pcrPolicy.getExpectedPcr().getIndex().toString(), pcrPolicy.getExpectedPcr().getValue().toString());
                    // find out which MLE this policy corresponds to and then log it
                    TblTaLog pcr = taLogMap.get(pcrPolicy.getExpectedPcr().getIndex());
                    // the pcr from the map will be null if it is not mentioned in the Required_Manifest_List of the mle.  for now, if someone has removed it from the required list we skip this. 
                    if( pcr == null ) {
                        //log.warn("Trust policy includes PCR {} but MLE does not define it", pcrPolicy.getExpectedPcr().getIndex().toInteger());
                        // create the missing pcr record in the report so the user will see it in the UI 
                        pcr = new TblTaLog();
                        // we need to find out if this is a bios pcr or vmm pcr
                        String[] markers = pcrPolicy.getMarkers();
                        List<String> markerList = Arrays.asList(markers);
                        if( markerList.contains("BIOS") ) {
                            log.info("MLE Type is BIOS");
                            //log.warn("MLE Type is BIOS");
                            pcr.setMleId(host.getBiosMleId().getId());
                        }
                        else if( markerList.contains("VMM") ) {
                            log.info("MLE Type is VMM");
                            //log.warn("MLE Type is VMM");
                            pcr.setMleId(host.getVmmMleId().getId());
                        }
                        else if ( markerList.contains("ASSET_TAG")) {
                            log.debug ("MLE type is ASSET_TAG");
                            pcr.setMleId(host.getVmmMleId().getId());
                        }
                        else {
                            //log.warn("MLE Type is unknown, markers are: {}", StringUtils.join(markers, ","));
                        }
                        pcr.setHostID(host.getId());
                        pcr.setHost_uuid_hex(host.getUuid_hex());
                        pcr.setUuid_hex(new UUID().toString());
                        pcr.setUpdatedOn(today);
                        pcr.setTrustStatus(true); // start as true, later we'll change to false if there are any faults 
                        pcr.setManifestName(pcrPolicy.getExpectedPcr().getIndex().toString());
                        if( report.getHostReport().pcrManifest == null || report.getHostReport().pcrManifest.getPcr(pcrPolicy.getExpectedPcr().getIndex()) == null ) {
                            throw new ASException(ErrorCode.AS_HOST_MANIFEST_MISSING_PCRS); // will cause the host to show up as "unknown" since there will not be any ta log records
                        }
                        pcr.setManifestValue(report.getHostReport().pcrManifest.getPcr(pcrPolicy.getExpectedPcr().getIndex()).getValue().toString());
                        taLogMap.put(pcrPolicy.getExpectedPcr().getIndex(), pcr);
                    }
                    pcr.setTrustStatus(result.isTrusted());
                    if( !result.isTrusted() ) {
                        pcr.setError("Incorrect value for PCR "+pcrPolicy.getExpectedPcr().getIndex().toString());
                    }
    //                pcr.setManifestName(pcrPolicy.getExpectedPcr().getIndex().toString());
    //                pcr.setManifestValue(report.getHostReport().pcrManifest.getPcr(pcrPolicy.getExpectedPcr().getIndex()).getValue().toString()); 
                    /*
                    if( biosPcrList.contains(pcrPolicy.getExpectedPcr().getIndex().toString()) ) {
                        pcr.setTrustStatus(true);
                        pcr.setMleId(host.getBiosMleId().getId());
                    }
                    if( vmmPcrList.contains(pcrPolicy.getExpectedPcr().getIndex().toString()) ) {
                        pcr.setTrustStatus(true);
                        pcr.setMleId(host.getVmmMleId().getId());
                        
                    }*/
                }
                if( rule instanceof PcrEventLogIntegrity ) { // for now assuming there is only one, for pcr 19...
                    PcrEventLogIntegrity eventLogIntegrityRule = (PcrEventLogIntegrity)rule;
                    TblTaLog pcr = taLogMap.get(eventLogIntegrityRule.getPcrIndex());
                    if (pcr != null) {
                        pcr.setTrustStatus(result.isTrusted()); 
                        if( !result.isTrusted() ) {
                            pcr.setError("No integrity in PCR "+eventLogIntegrityRule.getPcrIndex().toString());
                        }
                    }
    //                pcr.setError(null);
    //                pcr.setManifestName(eventLogIntegrityRule.getPcrIndex().toString());
    //                pcr.setManifestValue(report.getHostReport().pcrManifest.getPcr(eventLogIntegrityRule.getPcrIndex()).getValue().toString());
                    /*
                    if( biosPcrList.contains(eventLogIntegrityRule.getPcrIndex().toString()) ) {
                        pcr.setMleId(host.getBiosMleId().getId());
                    }
                    if( vmmPcrList.contains(eventLogIntegrityRule.getPcrIndex().toString()) ) {
                        pcr.setMleId(host.getVmmMleId().getId());
                    }
                    talogJpa.create(pcr);
                    */
                }
                // in mtwilson-1.1, the mw_module_manifest_log table is used to record only when host module values do not match the whitelist
                if( rule instanceof PcrEventLogIncludes ) {
                    /*
                    PcrEventLogIncludes eventLogRule = (PcrEventLogIncludes)rule;
                    Set<Measurement> measurements = eventLogRule.getMeasurements();
                    for(Measurement m : measurements) {
                        TblModuleManifestLog event = new TblModuleManifestLog();
                    }
                    */
                    List<Fault> faults = result.getFaults();
                    for(Fault fault : faults) {
                        if( fault instanceof PcrEventLogMissingExpectedEntries ) { // there would only be one of these faults per PcrEventLogIncludes rule.
                            PcrEventLogMissingExpectedEntries missingEntriesFault = (PcrEventLogMissingExpectedEntries)fault;

                            TblTaLog pcr = taLogMap.get(missingEntriesFault.getPcrIndex());
                            if (pcr != null) {
        //                        pcr.setHostID(host.getId());
                                pcr.setTrustStatus(false); // PCR not trusted since one or more required modules are missing, which we will detail below
                                pcr.setError("Missing modules");
        //                        pcr.setUpdatedOn(today);
        //                        pcr.setManifestName(missingEntriesFault.getPcrIndex().toString());    
        //                        pcr.setManifestValue(""); // doesn't match up with how we store data. we would need to look for another related fault about the dynamic value not matching... 
        //                        if( biosPcrList.contains(missingEntriesFault.getPcrIndex().toString()) ) {
        //                            pcr.setMleId(host.getBiosMleId().getId());
        //                        }
        //                        if( vmmPcrList.contains(missingEntriesFault.getPcrIndex().toString()) ) {
        //                            pcr.setMleId(host.getVmmMleId().getId());
        //                        }
                                talogJpa.create(pcr); // exception to creating all at the end... 

                                Set<Measurement> missingEntries = missingEntriesFault.getMissingEntries();
                                for(Measurement m : missingEntries) {
                                    // try to find the same module in the host report (hopefully it has the same name , and only the value changed)
                                    if( report.getHostReport().pcrManifest == null || report.getHostReport().pcrManifest.getPcrEventLog(missingEntriesFault.getPcrIndex()) == null ) {
                                        throw new ASException(ErrorCode.AS_MISSING_PCR_MANIFEST);
                                    }
                                    Measurement found = null;
                                    List<Measurement> actualEntries = report.getHostReport().pcrManifest.getPcrEventLog(missingEntriesFault.getPcrIndex()).getEventLog();
                                    for(Measurement a : actualEntries) {
                                        //  if( a.getInfo().get("ComponentName").equals(m.getLabel()) ) {
                                        if( a.getLabel().equals(m.getLabel()) ) {
                                            found = a;
                                        }
                                    }
                                    // does the host have a module with the same name but different value? if so, we should log it in TblModuleManifestLog... but from here we don't have access to the HostReport.
                                    TblModuleManifestLog event = new TblModuleManifestLog();
                                    event.setName(m.getLabel());
                                    event.setTaLogId(pcr);
                                    event.setValue( found == null ? "" : found.getValue().toString() ); // we don't know from our report what the "actual" value is since we only logged that an expected value was missing... so maybe there's a module with the same name and wrong value in the host report, which we don't know here... see comment above,  this probably needs to change.
                                    event.setWhitelistValue(m.getValue().toString());
                                    moduleLogJpa.create(event);
                                }
                            }
                        }
                    }
                }
                if( rule instanceof PcrEventLogEqualsExcluding ) {
                    log.debug("Processing the PcrEventLogEqualExcluding rule");
                    TblTaLog pcr;
                    List<Fault> faults = result.getFaults();
                    for(Fault fault : faults) {
                        if( fault instanceof PcrEventLogMissingExpectedEntries ) { // there would only be one of these faults per PcrEventLogIncludes rule.
                            log.debug("Host is missing modules compared to the white list.");
                            PcrEventLogMissingExpectedEntries missingEntriesFault = (PcrEventLogMissingExpectedEntries)fault;

                            pcr = taLogMap.get(missingEntriesFault.getPcrIndex());
                            if (pcr != null) {
                                if (pcr.getId() != null) {
                                    log.debug("TaTblLog ID {} already exists.", pcr.getId());
                                    pcr = talogJpa.findTblTaLog(pcr.getId());
                                }
                                pcr.setTrustStatus(false); 
                                if (pcr.getError()== null || pcr.getError().isEmpty())
                                    pcr.setError("Missing modules");
                                else
                                    pcr.setError(pcr.getError() + " and " + " Missing modules");
                                if (pcr.getId() == null) {
                                    log.debug("TaTblLog ID does not exist. Creating a new one. {}-{}", pcr.getTrustStatus(), pcr.getError());
                                    talogJpa.create(pcr);
                                } else {                            
                                    try {
                                        log.debug("Editing the existing TaTblLog ID. {}-{}", pcr.getTrustStatus(), pcr.getError());
                                        talogJpa.edit(pcr);
                                    } catch (IllegalOrphanException | NonexistentEntityException | ASDataException ex) {
                                        log.error("Error updating the status in the TaLog table.", ex);
                                    }
                                }
                                taLogMap.put(missingEntriesFault.getPcrIndex(), pcr);

                                Set<Measurement> missingEntries = missingEntriesFault.getMissingEntries();
                                for(Measurement m : missingEntries) {
                                    String mComponentName = m.getInfo().get("ComponentName");
                                    log.debug("Missing entry : " + mComponentName + "||" + m.getValue().toString());
                                    // try to find the same module in the host report (hopefully it has the same name , and only the value changed)
                                    if( report.getHostReport().pcrManifest == null || report.getHostReport().pcrManifest.getPcrEventLog(missingEntriesFault.getPcrIndex()) == null ) {
                                        throw new ASException(ErrorCode.AS_MISSING_PCR_MANIFEST);
                                    }
                                    Measurement found = null;
                                    List<Measurement> actualEntries = report.getHostReport().pcrManifest.getPcrEventLog(missingEntriesFault.getPcrIndex()).getEventLog();
                                    if (actualEntries != null) {
                                        for(Measurement a : actualEntries) {
                                            String aFullComponentName = a.getInfo().get("FullComponentName");
                                            if (a != null && a.getInfo() != null && (!a.getInfo().isEmpty())) {
                                                // log.debug("Actual Entries : " + a.getLabel() + "||" + a.getInfo().get("ComponentName") + "||" + a.getValue().toString() + "||" + a.getInfo().get("FullComponentName"));
                                                if( aFullComponentName != null && mComponentName != null && 
                                                        aFullComponentName.equals(mComponentName) ) {
                                                    found = a;
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    // does the host have a module with the same name but different value? if so, we should log it in TblModuleManifestLog... but from here we don't have access to the HostReport.
                                    TblModuleManifestLog findByTaLogIdAndName = moduleLogJpa.findByTaLogIdAndName(pcr, m.getInfo().get("ComponentName"));
                                    if (findByTaLogIdAndName == null) {
                                        TblModuleManifestLog event = new TblModuleManifestLog();
                                        event.setName(m.getInfo().get("ComponentName"));
                                        event.setTaLogId(pcr);
                                        event.setValue( found == null ? "" : found.getValue().toString() ); // we don't know from our report what the "actual" value is since we only logged that an expected value was missing... so maybe there's a module with the same name and wrong value in the host report, which we don't know here... see comment above,  this probably needs to change.
                                        event.setWhitelistValue(m.getValue().toString()); // since this is a additional module on the host, the white list would be null
                                        moduleLogJpa.create(event);
                                    } else {
                                        if (findByTaLogIdAndName.getValue() == null || findByTaLogIdAndName.getValue().isEmpty())
                                            findByTaLogIdAndName.setValue(found == null ? "" : found.getValue().toString() );
                                        if (findByTaLogIdAndName.getWhitelistValue() == null || findByTaLogIdAndName.getWhitelistValue().isEmpty())
                                            findByTaLogIdAndName.setWhitelistValue(m.getValue().toString());
                                        try {
                                            moduleLogJpa.edit(findByTaLogIdAndName);
                                        } catch (NonexistentEntityException | ASDataException ex) {
                                            log.error("Exception while updating the module manifest log record.", ex);
                                        }
                                    }
                                }
                            }
                        }
                        if( fault instanceof PcrEventLogContainsUnexpectedEntries ) { 
                            log.debug("Host is having additional modules compared to the white list");
                            PcrEventLogContainsUnexpectedEntries unexpectedEntriesFault = (PcrEventLogContainsUnexpectedEntries)fault;

                            pcr = taLogMap.get(unexpectedEntriesFault.getPcrIndex());
                            if (pcr != null) {
                                if (pcr.getId() != null) {
                                    log.debug("TaTblLog ID {} already exists.", pcr.getId());
                                    pcr = talogJpa.findTblTaLog(pcr.getId());
                                }
                                pcr.setTrustStatus(false);
                                if (pcr.getError() == null || pcr.getError().isEmpty())
                                    pcr.setError("Additional modules");
                                else
                                    pcr.setError(pcr.getError() + " and " + "Additional modules");
                                if (pcr.getId() == null) {
                                    log.debug("TaTblLog ID does not exist. Creating a new one. {}-{}", pcr.getTrustStatus(), pcr.getError());
                                    talogJpa.create(pcr);
                                } else {
                                    try {
                                        log.debug("Editing the existing TaTblLog ID. {}-{}", pcr.getTrustStatus(), pcr.getError());
                                        talogJpa.edit(pcr);
                                    } catch (Exception ex) {
                                        log.error("Error updating the status in the TaLog table.", ex);
                                    }
                                }
                                taLogMap.put(unexpectedEntriesFault.getPcrIndex(), pcr);

                                List<Measurement> unexpectedEntries = unexpectedEntriesFault.getUnexpectedEntries();
                                for(Measurement m : unexpectedEntries) {
                                    String mFullComponentName = m.getInfo().get("FullComponentName");
                                    log.debug("Unexpected Entry : " + mFullComponentName);
                                    // try to find the same module in the host report (hopefully it has the same name , and only the value changed)
                                    if( report.getHostReport().pcrManifest == null || report.getHostReport().pcrManifest.getPcrEventLog(unexpectedEntriesFault.getPcrIndex()) == null ) {
                                        throw new ASException(ErrorCode.AS_MISSING_PCR_MANIFEST);
                                    }
                                    Measurement found = null;
                                    List<Measurement> actualEntries = report.getHostReport().pcrManifest.getPcrEventLog(unexpectedEntriesFault.getPcrIndex()).getEventLog();
                                    if (actualEntries != null) {
                                        for(Measurement a : actualEntries) {
                                            String aFullComponentName = a.getInfo().get("FullComponentName");
                                            if ( a != null && a.getInfo() != null && (!a.getInfo().isEmpty())) {
                                                //log.debug("Actual Entries : " + a.getLabel() + "||" + a.getInfo().get("ComponentName") + "||" + 
                                                 //       a.getValue().toString() + "||" + a.getInfo().get("FullComponentName"));
                                                if( aFullComponentName != null && mFullComponentName != null && 
                                                        aFullComponentName.equals(mFullComponentName) ) {
                                                    found = a;
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    // does the host have a module with the same name but different value? if so, we should log it in TblModuleManifestLog... but from here we don't have access to the HostReport.
                                    TblModuleManifestLog findByTaLogIdAndName = moduleLogJpa.findByTaLogIdAndName(pcr, m.getInfo().get("ComponentName"));
                                    if (findByTaLogIdAndName == null) {
                                        TblModuleManifestLog event = new TblModuleManifestLog();
                                        event.setName(m.getInfo().get("FullComponentName"));
                                        event.setTaLogId(pcr);
                                        event.setValue( found == null ? "" : found.getValue().toString() ); // we don't know from our report what the "actual" value is since we only logged that an expected value was missing... so maybe there's a module with the same name and wrong value in the host report, which we don't know here... see comment above,  this probably needs to change.
                                        event.setWhitelistValue(""); // since this is a additional module on the host, the white list would be null
                                        moduleLogJpa.create(event);
                                    } else {
                                        if (findByTaLogIdAndName.getValue() == null || findByTaLogIdAndName.getValue().isEmpty())
                                            findByTaLogIdAndName.setValue(found == null ? "" : found.getValue().toString() );
                                        if (findByTaLogIdAndName.getWhitelistValue() == null || findByTaLogIdAndName.getWhitelistValue().isEmpty())
                                            findByTaLogIdAndName.setWhitelistValue("");
                                        try {
                                            moduleLogJpa.edit(findByTaLogIdAndName);
                                        } catch (NonexistentEntityException | ASDataException ex) {
                                            log.error("Exception while updating the module manifest log record.", ex);
                                        }
                                    }
                                }
                            }
                        }                    
                    }                    
                }

            }
            // now create all those mw_ta_log records (one per pcr)
            for(TblTaLog pcr : taLogMap.values()) {
                if( pcr.getId() == null ) {
                    talogJpa.create(pcr);
                }
    //            else {
    //                try {
    //                    log.debug("LogPcrTrustStatus : Adding the log details for {} - {}", pcr.getId(), (pcr.getTrustStatus() + "||" + pcr.getError()));
    //                    talogJpa.edit(pcr); // it it was already created (reasonable instance of PcrEventLogIncludes or not)
    //            }
    //                catch(Exception e) {
    //                    log.error("Error in logPcrTrustStatus.", e);
    //                    //e.printStackTrace(System.out);
    //                }
    //            }
            }
        } catch (IOException ex) {
            log.error("Error during logging of the PCR trust status", ex);
            throw new ASException(ErrorCode.SYSTEM_ERROR, ex.getClass().getSimpleName());
        }
    }

    private TblHosts getHostByName(Hostname hostName) throws IOException { // datatype.Hostname
        if( hostBO == null ) { throw new IllegalStateException("Invalid server configuration"); }
        try {
            TblHosts tblHost = hostBO.getHostByName(hostName);
            //Bug # 848 Check if the query returned back null or we found the host 
            if (tblHost == null ){
                throw new ASException(ErrorCode.AS_HOST_NOT_FOUND, hostName);
            }
            return tblHost;
        }
        catch(CryptographyException e) {
            throw new ASException(e,ErrorCode.AS_ENCRYPTION_ERROR, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        }
    }
    
    private TblHosts getHostByAik(Sha1Digest fingerprint) throws IOException  { // datatype.Hostname
        if( hostBO == null ) { throw new IllegalStateException("Invalid server configuration"); }
        try {
            return hostBO.getHostByAik(fingerprint);
        }
        catch(CryptographyException e) {
            throw new ASException(e,ErrorCode.AS_ENCRYPTION_ERROR, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        }
    }

    public OpenStackHostTrustLevelReport getPollHosts(OpenStackHostTrustLevelQuery input) {

        OpenStackHostTrustLevelReport hostTrusts = new OpenStackHostTrustLevelReport();
        Date today = new Date(System.currentTimeMillis());
        String trustLevel;

        for (Hostname hostName : input.hosts) {

            try {

                String hostTrustStatus = getTrustStatusString(hostName);

                log.debug("The trust status of {} is :{}",
                        hostName.toString(), hostTrustStatus);

                trustLevel = parseTrustStatus(hostTrustStatus);
            } catch (ASException e) {
                log.error( "Error while getting trust of host "
                        + hostName, e);
                trustLevel = "unknown";
            } catch (Exception e) {
                log.error( "Error while getting trust of host "
                        + hostName, e);
                trustLevel = "unknown";
            }
            HostTrustLevel1String trust = new HostTrustLevel1String();
            trust.hostname = hostName.toString();
            trust.trustLevel = trustLevel;
            trust.timestamp = Util.getDateString(today);
            hostTrusts.pollHosts.add(trust);

        }

        

        return hostTrusts;
    }

    private String parseTrustStatus(String hostTrustStatus) {
        String result = "untrusted";

        Boolean biostrust = false;
        Boolean vmmtrust = false;
        String[] parts = hostTrustStatus.split(",");

        for (String part : parts) {
            String[] subParts = part.split(":");
            if (subParts[0].equals("BIOS")) {
                biostrust = subParts[1].equals("1");
            } else {
                vmmtrust = subParts[1].equals("1");
            }

        }

        if (biostrust && vmmtrust) {
            result = "trusted";
        }

        return result;
    }

    // PREMIUM FEATURE ? 
    /**
     * Gets the location of the host from db table tblHosts
     *
     * @param hostName
     * @return {@link HostLocation}
     */
    public HostLocation getHostLocation(Hostname hostName) {
        try {
            TblHosts tblHosts = getHostByName(hostName);

            if (tblHosts == null) {
                throw new ASException(
                        ErrorCode.AS_HOST_NOT_FOUND,
                        String.format(
                        "%s",
                        hostName));
            }

            HostLocation location = new HostLocation(tblHosts.getLocation());
            return location;
        } catch (ASException e) {
            throw e;
        } catch (Exception ex) {
            // throw new ASException(e);
            log.error("Error during retrieval of host location.", ex);
            throw new ASException(ErrorCode.AS_HOST_LOCATION_ERROR, ex.getClass().getSimpleName());
        }
    }
    
    /**
     * Author: Sudhir
     * 
     * Add a new location mapping entry into the table.
     * 
     * @param hlObj
     * @return 
     */
    public Boolean addHostLocation(HostLocation hlObj) {

        try {
            TblLocationPcrJpaController locJpaController = My.jpa().mwLocationPcr();
            if (hlObj != null && !hlObj.white_list_value.isEmpty()) {
                TblLocationPcr locPCR = locJpaController.findTblLocationPcrByPcrValueEx(hlObj.white_list_value);
                if (locPCR != null) {
                    log.debug(String.format("An entry already existing in the location table for the white list specified [%s | %s]"
                            , locPCR.getLocation(), hlObj.white_list_value));
                    if (locPCR.getLocation().equals(hlObj.location)) {
                        // No need to do anything. Just exit.
                        return true;
                    }
                    else {
                        // Need to update the entry
                        //log.debug(String.format("Updating the location value for the white list specified to %s.", hlObj.location));
                        locPCR.setLocation(hlObj.location);
                        locJpaController.edit(locPCR);
                    }
                } else {
                    // Add a new entry for the location mapping table.
                    locPCR = new TblLocationPcr();
                    locPCR.setLocation(hlObj.location);
                    locPCR.setPcrValue(hlObj.white_list_value);
                    locJpaController.create(locPCR);
                    //log.debug(String.format("Successfully added a new location value %s with white list %s.", hlObj.location, hlObj.white_list_value));
                }
            }
        } catch (ASException e) {
            throw e;
        } catch (Exception ex) {
            // throw new ASException( e);
            log.error("Error during configuration of host location.", ex);
            throw new ASException(ErrorCode.AS_HOST_LOCATION_CONFIG_ERROR, ex.getClass().getSimpleName());
        }

        return true;
    }
    
    /**
     * Returns a multi-host SAML assertion.  It's similar to getTrustWithSaml(TblHosts,String)
     * but it does NOT save the generated SAML assertion.
     */
    public String getTrustWithSaml(Collection<TblHosts> tblHostsCollection) {
        try {
            //String location = hostTrustBO.getHostLocation(new Hostname(hostName)).location; // example: "San Jose"
            //HostTrustStatus trustStatus = hostTrustBO.getTrustStatus(new Hostname(hostName)); // example:  BIOS:1,VMM:1
            ArrayList<TxtHostWithAssetTag> hostList = new ArrayList<>();
            
            for(TblHosts tblHosts : tblHostsCollection) {
                // these 3 lines equivalent of getHostWithTrust without a host-specific saml assertion table record to update 
                HostTrustStatus trust = getTrustStatus(tblHosts, tblHosts.getUuid_hex()); 
                TxtHostRecord data = createTxtHostRecord(tblHosts);
                TxtHost host = new TxtHost(data, trust);

                // We need to add the Asset tag related data only if the host is provisioned for it. This is done
                // by verifying in the asset tag certificate table. 
                X509AttributeCertificate tagCertificate; 
                AssetTagCertBO atagCertBO = new AssetTagCertBO();
                MwAssetTagCertificate atagCertForHost = atagCertBO.findValidAssetTagCertForHost(tblHosts.getHardwareUuid());
                if (atagCertForHost != null) {
                    tagCertificate = X509AttributeCertificate.valueOf(atagCertForHost.getCertificate());
                } else {
                    tagCertificate = null;
                }
                
                /*
                // We will check if the asset-tag was verified successfully for the host. If so, we need to retrieve
                // all the attributes for that asset-tag and send it to the saml generator.
                X509AttributeCertificate tagCertificate = null; 
                if (host.isAssetTagTrusted()) {
                    AssetTagCertBO atagCertBO = new AssetTagCertBO();
                    MwAssetTagCertificate atagCertForHost = atagCertBO.findValidAssetTagCertForHost(tblHosts.getHardwareUuid());
                    if (atagCertForHost != null) {
                        tagCertificate = X509AttributeCertificate.valueOf(atagCertForHost.getCertificate());
//                        atags.add(new AttributeOidAndValue("UUID", atagCertForHost.getUuid())); // should already be the "Subject" attribute of the certificate, if not then we need to get it from one of the cert attributes
                    }
                }*/
                
                TxtHostWithAssetTag hostWithAssetTag = new TxtHostWithAssetTag(host, tagCertificate);
                hostList.add(hostWithAssetTag);
            }
            
            SamlAssertion samlAssertion = getSamlGenerator().generateHostAssertions(hostList);

            log.debug("Expiry {}" , samlAssertion.expiry_ts.toString());

            return samlAssertion.assertion ;
        } catch (ASException e) {
            // ASException sets HTTP Status to 400 for all errors
            // We override that here to give more specific codes when possible:
            if (e.getErrorCode().equals(ErrorCode.AS_HOST_NOT_FOUND)) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
            /*
             * if( e.getErrorCode().equals(ErrorCode.TA_ERROR)) { throw new
             * WebApplicationException(Status.INTERNAL_SERVER_ERROR); }
             *
             */
            throw e;
        } catch (Exception ex) {
            // throw new ASException( e);
            log.error("Error during retrieval of host trust status.", ex);
            throw new ASException(ErrorCode.AS_HOST_TRUST_ERROR, ex.getClass().getSimpleName());
        }
        
    }

    /**
     * @param tblHosts
     * @param hostId
     * @return
     */
    public String getTrustWithSaml(TblHosts tblHosts, String hostId) {
        String hostAttestationUuid = new UUID().toString();
        log.debug("Generating new UUID for saml assertion record 2: {}", hostAttestationUuid);
        return getTrustWithSaml(tblHosts, hostId, hostAttestationUuid).getSaml();
    }
    
    /**
     * @param tblHosts
     * @param hostId
     * @param hostAttestationUuid
     * @return
     */
    public HostAttestation getTrustWithSaml(TblHosts tblHosts, String hostId, String hostAttestationUuid) {
        try {
            //String location = hostTrustBO.getHostLocation(new Hostname(hostName)).location; // example: "San Jose"
            //HostTrustStatus trustStatus = hostTrustBO.getTrustStatus(new Hostname(hostName)); // example:  BIOS:1,VMM:1
            
            TblSamlAssertion tblSamlAssertion = new TblSamlAssertion();

            TxtHost host = getHostWithTrust(tblHosts, hostId,tblSamlAssertion);
            
            tblSamlAssertion.setAssertionUuid(hostAttestationUuid);
            tblSamlAssertion.setBiosTrust(host.isBiosTrusted());
            tblSamlAssertion.setVmmTrust(host.isVmmTrusted());

            // We need to add the Asset tag related data only if the host is provisioned for it. This is done
            // by verifying in the asset tag certificate table. 
            X509AttributeCertificate tagCertificate; 
            AssetTagCertBO atagCertBO = new AssetTagCertBO();
            MwAssetTagCertificate atagCertForHost = atagCertBO.findValidAssetTagCertForHost(tblSamlAssertion.getHostId().getId());
            if (atagCertForHost != null) {
                log.debug("Host has been provisioned in the system with a TAG.");
                tagCertificate = X509AttributeCertificate.valueOf(atagCertForHost.getCertificate());
            } else {
                log.debug("Host has not been provisioned in the system with a TAG.");
                tagCertificate = null;
            }

            SamlAssertion samlAssertion = getSamlGenerator().generateHostAssertion(host, tagCertificate);

            
            // We will check if the asset-tag was verified successfully for the host. If so, we need to retrieve
            // all the attributes for that asset-tag and send it to the saml generator.
/*            X509AttributeCertificate tagCertificate = null; 
            if (host.isAssetTagTrusted()) {
                AssetTagCertBO atagCertBO = new AssetTagCertBO();
                MwAssetTagCertificate atagCertForHost = atagCertBO.findValidAssetTagCertForHost(tblSamlAssertion.getHostId().getId());
                if (atagCertForHost != null) {
                    tagCertificate = X509AttributeCertificate.valueOf(atagCertForHost.getCertificate());
//                        atags.add(new AttributeOidAndValue("UUID", atagCertForHost.getUuid())); // should already be the "Subject" attribute of the certificate, if not then we need to get it from one of the cert attributes
                }
            }

            SamlAssertion samlAssertion = getSamlGenerator().generateHostAssertion(host, tagCertificate);
*/
            log.debug("Expiry {}" , samlAssertion.expiry_ts.toString());

            tblSamlAssertion.setSaml(samlAssertion.assertion);
            tblSamlAssertion.setExpiryTs(samlAssertion.expiry_ts);
            tblSamlAssertion.setCreatedTs(samlAssertion.created_ts);
            
            TrustReport hostTrustReport = getTrustReportForHost(tblHosts, tblHosts.getName());
            tblSamlAssertion.setTrustReport(mapper.writeValueAsString(hostTrustReport));
            logTrustReport(tblHosts, hostTrustReport); // Need to cache the attestation report ### v1 requirement to log to mw_ta_log
                
            My.jpa().mwSamlAssertion().create(tblSamlAssertion);

            return buildHostAttestation(tblHosts, tblSamlAssertion);
        } catch (ASException e) {
            // ASException sets HTTP Status to 400 for all errors
            // We override that here to give more specific codes when possible:
            if (e.getErrorCode().equals(ErrorCode.AS_HOST_NOT_FOUND)) {
                throw new WebApplicationException(Status.NOT_FOUND);
            }
            /*
             * if( e.getErrorCode().equals(ErrorCode.TA_ERROR)) { throw new
             * WebApplicationException(Status.INTERNAL_SERVER_ERROR); }
             *
             */
            throw e;
        } catch (Exception ex) {
            // throw new ASException( e);
            log.error("Error during retrieval of host trust status.", ex);
            throw new ASException(ErrorCode.AS_HOST_TRUST_ERROR, ex.getClass().getSimpleName());
        }
    }

    private SamlGenerator getSamlGenerator() throws UnknownHostException, ConfigurationException, IOException {
        Configuration conf = My.configuration().getConfiguration();
        InetAddress localhost = InetAddress.getLocalHost();
        String defaultIssuer = "https://" + localhost.getHostAddress() + ":8181/AttestationService"; 
        String issuer = conf.getString("saml.issuer", defaultIssuer);
        SamlGenerator saml = new SamlGenerator(samlKeystoreResource, conf);
        saml.setIssuer(issuer);
        return saml;
    }
    
    public String getTrustWithSamlByAik(Sha1Digest aik, boolean forceVerify) throws IOException {
        My.initDataEncryptionKey();
        TblHosts tblHosts = getHostByAik(aik);
        return getTrustWithSaml(tblHosts, aik.toString(), forceVerify);
    }

    public String getTrustWithSaml(String host, boolean forceVerify) throws IOException {
        My.initDataEncryptionKey();
        TblHosts tblHosts = getHostByName(new Hostname((host)));
        return getTrustWithSaml(tblHosts, tblHosts.getName(), forceVerify);
    }

    public String getTrustWithSamlForHostnames(Collection<String> hosts) throws IOException {
        My.initDataEncryptionKey();
        ArrayList<TblHosts> tblHostsList = new ArrayList<TblHosts>();
        for(String host : hosts) {
            TblHosts tblHosts = getHostByName(new Hostname((host)));
            tblHostsList.add(tblHosts);
        }
        return getTrustWithSaml(tblHostsList);
    }
        
    public String getTrustWithSaml(TblHosts tblHosts, String hostId, boolean forceVerify) throws IOException {
        String hostAttestationUuid = new UUID().toString();
        log.debug("Generating new UUID for saml assertion record 1: {}", hostAttestationUuid);
        return getTrustWithSaml(tblHosts, hostId, hostAttestationUuid, forceVerify).getSaml();
    }
    
    public HostAttestation getTrustWithSaml(TblHosts tblHosts, String hostId, String hostAttestationUuid, boolean forceVerify) throws IOException {
        log.debug("getTrustWithSaml: Getting trust for host: " + tblHosts.getName() + " Force verify flag: " + forceVerify);
        // Bug: 702: For host not supporting TXT, we need to return back a proper error
        // make sure the DEK is set for this thread
        
//        My.initDataEncryptionKey();
//        TblHosts tblHosts = getHostByName(new Hostname((host)));
        HostAgentFactory factory = new HostAgentFactory();
        HostAgent agent = factory.getHostAgent(tblHosts);
       // log.info("Value of the TPM flag is : " +  Boolean.toString(agent.isTpmEnabled()));
        
        if (!agent.isTpmPresent()) {
            throw new ASException(ErrorCode.AS_TPM_NOT_SUPPORTED, hostId);
        }
        if(forceVerify != true){
            //TblSamlAssertion tblSamlAssertion = new TblSamlAssertionJpaController((getEntityManagerFactory())).findByHostAndExpiry(hostId);
            TblSamlAssertion tblSamlAssertion = My.jpa().mwSamlAssertion().findByHostAndExpiry(tblHosts.getName()); //hostId);
            if(tblSamlAssertion != null){
                if(tblSamlAssertion.getErrorMessage() == null|| tblSamlAssertion.getErrorMessage().isEmpty()) {
                    log.debug("Found assertion in cache. Expiry time : " + tblSamlAssertion.getExpiryTs());
                    return buildHostAttestation(tblHosts, tblSamlAssertion);
                } else {
                    log.debug("Found assertion in cache with error set, returning that.");
                   throw new ASException(new Exception("("+ tblSamlAssertion.getErrorCode() + ") " + tblSamlAssertion.getErrorMessage() + " (cached on " + tblSamlAssertion.getCreatedTs().toString()  +")"));
                }
            }
        }
        
        log.debug("Getting trust and saml assertion from host.");
        
        try {
//            return getTrustWithSaml(tblHosts, hostId);
            return getTrustWithSaml(tblHosts, hostId, hostAttestationUuid);
        }catch(Exception e) {
            TblSamlAssertion tblSamlAssertion = new TblSamlAssertion();
            tblSamlAssertion.setAssertionUuid(hostAttestationUuid);
            tblSamlAssertion.setHostId(tblHosts);
            //TxtHost hostTxt = getHostWithTrust(new Hostname(host),tblSamlAssertion); 
            //TxtHostRecord tmp = new TxtHostRecord();
            //tmp.HostName = host;
            //tmp.IPAddress = host;
            //TxtHost hostTxt = new TxtHost(tmp);
            
            tblSamlAssertion.setBiosTrust(false);
            tblSamlAssertion.setVmmTrust(false);
            
            try {
                log.error("Caught exception, generating saml assertion");
                log.error("Printing stacktrace first");
                e.printStackTrace();
                tblSamlAssertion.setSaml("");
                int cacheTimeout=ASConfig.getConfiguration().getInt("saml.validity.seconds",3600);
                tblSamlAssertion.setCreatedTs(Calendar.getInstance().getTime());
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.SECOND, cacheTimeout);
                tblSamlAssertion.setExpiryTs(cal.getTime());
                if(e instanceof ASException){
                    ASException ase = (ASException) e;
                    log.debug("e is an instance of ASExpection: " +String.valueOf(ase.getErrorCode()));
                    tblSamlAssertion.setErrorCode(String.valueOf(ase.getErrorCode()));
                }else{
                    log.debug("e is NOT an instance of ASExpection: " +String.valueOf(ErrorCode.AS_HOST_TRUST_ERROR.getErrorCode()));
                    tblSamlAssertion.setErrorCode(String.valueOf(ErrorCode.AS_HOST_TRUST_ERROR.getErrorCode()));
                }
                // tblSamlAssertion.setErrorMessage(e.getMessage());
                // Bug fix for 1038
                tblSamlAssertion.setErrorMessage(e.getClass().getSimpleName());
                My.jpa().mwSamlAssertion().create(tblSamlAssertion);
            }catch(Exception ex){
                //log.debug("getTrustwithSaml caugh exception while generating error saml assertion");
                log.error("getTrustwithSaml caugh exception while generating error saml assertion", ex);
                // String msg = ex.getMessage();
                String msg = ex.getClass().getSimpleName();
                // log.debug(msg);
                // throw new ASException(new Exception("getTrustWithSaml " + msg));
                throw new ASException(ex, ErrorCode.AS_HOST_TRUST_ERROR, msg);
                //throw new ASException(new Exception("Host Manifest is missing required PCRs."));
            } 
            //Daniel, change the messages into meaningful thiings here
            //log.debug("e.getMessage = "+e.getMessage());
            //throw new ASException(new Exception(e.getMessage()));
            log.error("Error during retrieval of host trust status.", e);
            throw new ASException(e, ErrorCode.AS_HOST_TRUST_ERROR, e.getClass().getSimpleName());
            //throw new ASException(new Exception("Host Manifest is missing required PCRs."));
        }
    }
    
    public HostAttestation buildHostAttestation(TblHosts tblHosts, TblSamlAssertion tblSamlAssertion) throws IOException {
        HostAttestation hostAttestation = new HostAttestation();
        hostAttestation.setAikSha1(tblHosts.getAikSha1());
        //hostAttestation.setChallenge(tblHosts.getChallenge());
        hostAttestation.setHostName(tblHosts.getName());
        hostAttestation.setHostUuid(tblHosts.getUuid_hex()); //.getHardwareUuid());
        hostAttestation.setId(UUID.valueOf(tblSamlAssertion.getAssertionUuid()));
        hostAttestation.setSaml(tblSamlAssertion.getSaml());
        hostAttestation.setTrustReport(mapper.readValue(tblSamlAssertion.getTrustReport(), TrustReport.class));

        HostTrustStatus hostTrustStatus = new HostTrustStatus();
        hostTrustStatus.bios = tblSamlAssertion.getBiosTrust();
        hostTrustStatus.vmm = tblSamlAssertion.getVmmTrust();
        //hostTrustStatus.asset_tag = tblSamlAssertion.getAssetTagTrust();
        hostAttestation.setHostTrustResponse(new HostTrustResponse(new Hostname(tblHosts.getName()), hostTrustStatus));
        return hostAttestation;
    }

    public HostTrust getTrustWithCache(String host, Boolean forceVerify) {
        log.debug("getTrustWithCache: Getting trust for host: " + host + " Force verify flag: " + forceVerify);
        try {
            
            if(forceVerify != true){
                TblHosts tblHosts = getHostByName(new Hostname(host));
                if(tblHosts != null){
                    TblTaLog tblTaLog = My.jpa().mwTaLog().getHostTALogEntryBefore(tblHosts.getId() , getCacheStaleAfter() );

                    // Bug 849: We need to ensure that we add the host name to the response as well. Otherwise it will just contain BIOS and VMM status.
                    if(tblTaLog != null) {
                        HostTrust hostTrust = getHostTrustObj(tblTaLog);
                        hostTrust.setIpAddress(host);
                        return hostTrust;
                    }
                }else{
                    throw new ASException(
                            ErrorCode.AS_HOST_NOT_FOUND,
                                       host);
                }
            }
        
           log.debug("Getting trust and saml assertion from host.");
        
           HostTrustStatus status = getTrustStatus(new Hostname(host));
           
           HostTrust hostTrust = new HostTrust(ErrorCode.OK,"OK");
           hostTrust.setBiosStatus((status.bios)?1:0);
           hostTrust.setVmmStatus((status.vmm)?1:0);
           hostTrust.setIpAddress(host);
           log.debug("JSONTrust is : ", host + ":" + Boolean.toString(status.bios) + ":" + Boolean.toString(status.vmm));
           return hostTrust;
            
        } catch (ASException e) {
            log.error("Error while getting trust for host " + host,e );
            //System.err.println("JIM DEBUG");
            return new HostTrust(e.getErrorCode(),e.getErrorMessage(),host,null,null);
        }catch(Exception e){
            log.error("Error while getting trust for host " + host,e );
            //System.err.println("JIM DEBUG"); 
            //e.printStackTrace(System.err);
            // return new HostTrust(ErrorCode.SYSTEM_ERROR, new AuthResponse(ErrorCode.SYSTEM_ERROR,e.getMessage()).getErrorMessage(),host,null,null);
            return new HostTrust(ErrorCode.AS_HOST_TRUST_ERROR, new AuthResponse(ErrorCode.AS_HOST_TRUST_ERROR,e.getClass().getSimpleName()).getErrorMessage(),host,null,null);
        }

    }
    
    public HostTrustStatus getTrustStatusWithCache(String host, Boolean forceVerify) throws ASException {
        log.debug("getTrustStatusWithCache: Getting trust for host: " + host + " Force verify flag: " + forceVerify);
        
        try {
            if(forceVerify != true){
                TblHosts tblHosts = getHostByName(new Hostname(host));
                if(tblHosts != null){
                    TblTaLog tblTaLog = My.jpa().mwTaLog().getHostTALogEntryBefore(tblHosts.getId() , getCacheStaleAfter() );

                    // Bug 849: We need to ensure that we add the host name to the response as well. Otherwise it will just contain BIOS and VMM status.
                    if(tblTaLog != null) {
                        HostTrustStatus hts = getHostTrustStatusObj(tblTaLog);
                        return hts;
                    }
                }else{
                    throw new ASException(ErrorCode.AS_HOST_NOT_FOUND, host);
                }
            }
        
           log.info("Getting trust and saml assertion from host.");
           HostTrustStatus status = getTrustStatus(new Hostname(host));
           return status;
            
        } catch (ASException ase) {
            log.error("Error while getting trust for host " + host,ase );
            throw ase;
        }catch(Exception e){
            log.error("Error while getting trust for host " + host,e );
            // throw new ASException(e);
            throw new ASException(ErrorCode.AS_HOST_TRUST_ERROR, e.getClass().getSimpleName());
        }

    }
    
    private Date getCacheStaleAfter(){
        return new DateTime().minusSeconds(CACHE_VALIDITY_SECS).toDate();
    }
    private HostTrust getHostTrustObj(TblTaLog tblTaLog) {
        HostTrust hostTrust = new HostTrust(ErrorCode.OK,"");
        
        String[] parts = tblTaLog.getError().split(",");
        
        for(String part : parts){
            String[] subparts = part.split(":");
            if(subparts[0].equalsIgnoreCase("BIOS")){
                hostTrust.setBiosStatus(Integer.valueOf(subparts[1]));
            }else{
                hostTrust.setVmmStatus(Integer.valueOf(subparts[1]));
            }
        }
        return hostTrust;
    }
    
    private HostTrustStatus getHostTrustStatusObj(TblTaLog tblTaLog) {
        HostTrustStatus hostTrustStatus = new HostTrustStatus();
        
        String[] parts = tblTaLog.getError().split(",");
        
        for(String part : parts){
            String[] subparts = part.split(":");
            if(subparts[0].equalsIgnoreCase("BIOS")){
                hostTrustStatus.bios = (Integer.valueOf(subparts[1]) != 0);
            }else{
                hostTrustStatus.vmm = (Integer.valueOf(subparts[1]) != 0);
            }
        }
        return hostTrustStatus;
    }

    public String checkMatchingMLEExists(HostConfigData hostConfigData) {
        boolean biosMLEFound = false, VMMMLEFound = false;
        
        try {
            TxtHostRecord hostObj = hostConfigData.getTxtHostRecord();
            String biosPCRs = hostConfigData.getBiosPCRs();
            String vmmPCRs = hostConfigData.getVmmPCRs();
            
            long getTrustStatusOfHostNotInDBStart = System.currentTimeMillis();
            
            log.debug("checkMatchingMLEExists: Starting to find the matching MLEs for host {}.", hostObj.HostName);
            TblMleJpaController mleJpa = My.jpa().mwMle();
            
            TblHosts tblHosts = new TblHosts();
            tblHosts.setName(hostObj.HostName);
            tblHosts.setAddOnConnectionInfo(hostObj.AddOn_Connection_String);
            tblHosts.setTlsPolicyChoice(hostObj.tlsPolicyChoice);  // either a tls policy id or a tls policy descriptor
            tblHosts.setIPAddress(hostObj.HostName);
            if (hostObj.Port != null) {
                tblHosts.setPort(hostObj.Port);
            }
            
            HostAgentFactory factory = new HostAgentFactory();
            HostAgent agent = factory.getHostAgent(tblHosts);
            if( !agent.isTpmEnabled() || !agent.isIntelTxtEnabled() ) {
                throw new ASException(ErrorCode.AS_INTEL_TXT_NOT_ENABLED, hostObj.HostName);
            }

//            PcrManifest pcrManifest = agent.getPcrManifest();
//            if( pcrManifest == null ) {
//                throw new ASException(ErrorCode.AS_HOST_MANIFEST_MISSING_PCRS);
//            }

            HostReport hostReport = new HostReport();
            hostReport.pcrManifest = null;
            hostReport.tpmQuote = null; 
            hostReport.variables = new HashMap<String,String>(); 

            log.debug("checkMatchingMLEExists: Successfully retrieved the TPM measurements from host '{}' for checking against the matching MLE.", hostObj.HostName);
            HostTrustPolicyManager hostTrustPolicyFactory = new HostTrustPolicyManager(My.persistenceManager().getASData());
            
            // We need to check for the BIOS MLE matching only if the user wants. This is applicable in cases where the user wants to just
            // create the new VMM MLE instead of both.
            if (biosPCRs != null && !biosPCRs.isEmpty()) {
                // First let us find the matching BIOS MLE for the host. This should retrieve all the MLEs with additional
                // numeric extensions if any.
                
                // In order to search for the matching BIOS name, we also need to add the target type and target value as filter criteria
                // Let us take 2 EPSD hosts having the exact same BIOS. If the user wants to create 2 white lists for both the hosts, without
                // adding this filter criteria of HostName, for the Host #2 we would match the Whitelist of Host #1 and hence we would not create
                // a new white list. 
                String targetType = hostConfigData.getBiosWLTarget().getValue();
                String targetValue = "";
                switch(hostConfigData.getBiosWLTarget()) {
                    case BIOS_OEM :
                        targetValue = hostObj.BIOS_Oem;
                        break;
                    case BIOS_HOST :
                        targetValue = hostObj.HostName;
                        break;
                    default :
                        targetValue = "";
                }
                
                log.debug("checkMatchingMLEExists: Retrieving the list of MLEs with version {} for OEM {} having target {}-{} for matching the whitelists.", 
                        hostObj.BIOS_Version, hostObj.BIOS_Oem, targetType, targetValue);
                
                List<TblMle> biosMLEList = mleJpa.findBiosMleByTarget(hostObj.BIOS_Version, hostObj.BIOS_Oem, targetType, targetValue);
                if (biosMLEList != null && !biosMLEList.isEmpty()) {
                    for (TblMle biosMLE : biosMLEList) {
                        log.debug("checkMatchingMLEExists: Processing BIOS MLE {} with version {}.", biosMLE.getName(), biosMLE.getVersion());

                        // If the list of bios PCRs that need to be configured does not match the list of the MLE in the DB, we have to create a new MLE
                        // So, we can skip the current one and check the remaining if exists.
                        if (!doPcrsListMatch(biosPCRs, biosMLE.getRequiredManifestList())) {
                            log.debug("checkMatchingMLEExists: Skipping BIOS MLE {} with version {} as the PCR list does not match.", biosMLE.getName(), biosMLE.getVersion());                        
                            continue;
                        }

                        // Now that all the basic validation is done, we can retrieve the attestation report from the host for verfiication against the DB. We were
                        // earlier retrieving the attestation report to start with. But for better performance, doing it after all the validations.
                        if (hostReport.pcrManifest == null) {
                            PcrManifest pcrManifest = agent.getPcrManifest();
                            if( pcrManifest == null ) {
                                throw new ASException(ErrorCode.AS_HOST_MANIFEST_MISSING_PCRS);
                            }
                            hostReport.pcrManifest = pcrManifest;
                        }
                        
                        // Bug-1014: due to new policy enforcement for AIK validation
                        if (agent.isAikCaAvailable()) {
                            hostReport.aik = new Aik(agent.getAikCertificate());
                        }
                        else if (agent.isAikAvailable()) {
                            hostReport.aik = new Aik(agent.getAik());
                        }
                        
                        tblHosts.setBiosMleId(biosMLE);

                        Policy trustPolicy = hostTrustPolicyFactory.loadTrustPolicyForMLEVerification(tblHosts, tblHosts.getName()); 
                        PolicyEngine policyEngine = new PolicyEngine();
                        TrustReport trustReport = policyEngine.apply(hostReport, trustPolicy);

                        if (trustReport != null && trustReport.isTrustedForMarker(TrustMarker.BIOS.name())) {
                            // We found the MLE to map to. So, need not create a new BIOS MLE. We can use the existing one
                            log.info("checkMatchingMLEExists: BIOS MLE '{}' matches the whitelists on whitelisting host '{}'. No new BIOS MLE would be created", biosMLE.getName(), hostObj.HostName);
                            biosMLEFound = true;
                            break;
                        } else {
                            // Since there was a mismatch we need to continue looking for additonal MLEs.
                            log.debug("checkMatchingMLEExists: BIOS MLE '{}' does not match the whitelists from the whitelisting host '{}'.", biosMLE.getName(), hostObj.HostName);
                        }
                    }
                } else {
                    log.info("checkMatchingMLEExists: BIOS MLE search for '{}' did not retrieve any matching MLEs. New BIOS MLE would be created.", hostObj.BIOS_Name);
                }

                // If at the end of the above loop, we do not find any BIOS MLE, we will create a new one.
                if (!biosMLEFound) {
                    log.info("checkMatchingMLEExists: No matching BIOS MLE found. New BIOS MLE would be created.");
                }

                // Reset the BIOS MLE ID so that we don't verify that again along with VMM MLE
                tblHosts.setBiosMleId(null);
            }
            
            if (vmmPCRs != null && !vmmPCRs.isEmpty()) {
                
                // In order to search for the matching VMM MLE, we also need to add the target type and target value as filter criteria
                // Let us take 2 different OEM boxes having the exact same ESXi version installed. Lets say that the user whitelists using
                // OEM Host #1. Assume that it creates VMM MLE with name DELL_Romley_VMware_ESXi. Now if the user wants to create the second
                // white list using the HP host, then we need to not only compare the VMM version, OS name and OS version but also consider
                // the OEM of the box from which the first whitelist was created. Otherwise it would match with the whitelist of Host #1 and
                // we would not create the second white list.
                String targetType = hostConfigData.getVmmWLTarget().getValue();
                String targetValue = "";
                switch(hostConfigData.getVmmWLTarget()) {
                    case VMM_OEM :
                        targetValue = hostObj.BIOS_Oem;
                        break;
                    case VMM_HOST :
                        targetValue = hostObj.HostName;
                        break;
                    case VMM_GLOBAL :
                    default :
                        targetValue = "";
                }

                log.debug("checkMatchingMLEExists: Retrieving the list of MLEs with VMM version {} for OS {} - {} having target {} - {} for matching the whitelists.", 
                        hostObj.VMM_Version, hostObj.VMM_OSName, hostObj.VMM_OSVersion, targetType, targetValue);
                
                // First let us find the matching VMM MLEs for the host that is configured in the system.
                List<TblMle> vmmMLEList = mleJpa.findVmmMleByTarget(hostObj.VMM_Version, hostObj.VMM_OSName, hostObj.VMM_OSVersion, targetType, targetValue);
                if (vmmMLEList != null && !vmmMLEList.isEmpty()) {
                    for (TblMle vmmMLE : vmmMLEList) {

                        log.debug("checkMatchingMLEExists: Processing VMM MLE {} with version {}.", vmmMLE.getName(), vmmMLE.getVersion());                    

                        // If the list of bios PCRs that need to be configured does not match the list of the MLE in the DB, we have to create a new MLE
                        // So, we can skip the current one and check the remaining if exists.
                        if (!doPcrsListMatch(vmmPCRs, vmmMLE.getRequiredManifestList())) {
                            log.debug("checkMatchingMLEExists: Skipping VMM MLE {} with version {} as the PCR list does not match.", vmmMLE.getName(), vmmMLE.getVersion());                        
                            continue;
                        }

                        // Now that all the basic validation is done, we can retrieve the attestation report from the host for verfiication against the DB. We were
                        // earlier retrieving the attestation report to start with. But for better performance, doing it after all the validations.
                        if (hostReport.pcrManifest == null) {
                            PcrManifest pcrManifest = agent.getPcrManifest();
                            if( pcrManifest == null ) {
                                throw new ASException(ErrorCode.AS_HOST_MANIFEST_MISSING_PCRS);
                            }
                            hostReport.pcrManifest = pcrManifest;
                        }

                        tblHosts.setVmmMleId(vmmMLE);

                        Policy trustPolicy = hostTrustPolicyFactory.loadTrustPolicyForMLEVerification(tblHosts, tblHosts.getName()); 
                        PolicyEngine policyEngine = new PolicyEngine();
                        TrustReport trustReport = policyEngine.apply(hostReport, trustPolicy);


                        if (trustReport != null && trustReport.isTrustedForMarker(TrustMarker.VMM.name())) {
                            // We found the MLE to map to. 
                            log.info("checkMatchingMLEExists: VMM MLE '{}' matches the whitelist from the whitelisting host '{}'. No new VMM MLE would be created.", 
                                    vmmMLE.getName(), hostObj.HostName);
                            VMMMLEFound = true;
                            break;
                        } else {
                            // Since there was a mismatch we need to continue looking for additonal MLEs. We still need to track
                            // the BIOS MLE name so that if nothing matches, we assign the host to the last MLE found.
                            log.debug("checkMatchingMLEExists: VMM MLE '{}' does not match whitelist from the whitelisting host '{}'.", vmmMLE.getName(), hostObj.HostName);
                        }                        
                    }
                } else {
                    log.info("checkMatchingMLEExists: VMM MLE search for '{}' did not retrieve any matching MLEs. New VMM MLE would be created.", hostObj.VMM_Name);
                }

                // If at the end of the above loop, we do not find any VMM MLE matching the whitelisting host we need to create a new VMM MLE.
                if (!VMMMLEFound) {
                    log.info("checkMatchingMLEExists: No matching VMM MLE found. New VMM MLE would be created.");
                }
            }    
            
            long getTrustStatusOfHostNotInDBStop = System.currentTimeMillis();
            log.debug("checkMatchingMLEExists performance overhead is {} milliseconds", (getTrustStatusOfHostNotInDBStop - getTrustStatusOfHostNotInDBStart));            
            
            return "BIOS:"+biosMLEFound+"|VMM:"+VMMMLEFound;
            
        } catch (ASException ae) {
            throw ae;
                        
        } catch (Exception ex) {
            // If in case we get any exception, we just return back the default names so that we don't end up with aborting the complete operation.            
            log.error("Error during host registration/update. ", ex);
            throw new ASException(ErrorCode.AS_REGISTER_HOST_ERROR, ex.getClass().getSimpleName());
        }        
    }
    
    private boolean doPcrsListMatch(String requestedPCRs, String dbMLEPCRs) {
        
        Collection<String> pcrsFromDB = Arrays.asList(dbMLEPCRs.split(","));
        Collection<String> pcrsFromRequest = Arrays.asList(requestedPCRs.split(","));

        if (!(pcrsFromDB.size() == pcrsFromRequest.size()) || !(pcrsFromDB.containsAll(pcrsFromRequest)) || !(pcrsFromRequest.containsAll(pcrsFromDB)))
            return false;
                    
        return true;
    }
    /*
    private void saveModuleManifestLog(PcrModuleManifest pcrModuleManifest, TblTaLog taLog) {
        TblModuleManifestLogJpaController controller = new TblModuleManifestLogJpaController(getEntityManagerFactory());
        for(ModuleManifest moduleManifest : pcrModuleManifest.getUntrustedModules()){
            TblModuleManifestLog moduleManifestLog = new TblModuleManifestLog();
            moduleManifestLog.setTaLogId(taLog);
            moduleManifestLog.setName(moduleManifest.getComponentName());
            moduleManifestLog.setValue(moduleManifest.getDigestValue());
            moduleManifestLog.setWhitelistValue(moduleManifest.getWhiteListValue());
            log.info("Adding the module manifest log.");
            controller.create(moduleManifestLog);
        }
    }
    */
}
