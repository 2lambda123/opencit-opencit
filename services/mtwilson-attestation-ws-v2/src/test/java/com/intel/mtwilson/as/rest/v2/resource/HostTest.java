/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.intel.mtwilson.as.rest.v2.resource;

import com.intel.dcsg.cpg.extensions.Extensions;
import com.intel.dcsg.cpg.io.UUID;
import com.intel.mtwilson.agent.VendorHostAgentFactory;
import com.intel.mtwilson.agent.citrix.CitrixHostAgentFactory;
import com.intel.mtwilson.agent.intel.IntelHostAgentFactory;
import com.intel.mtwilson.agent.vmware.VmwareHostAgentFactory;
import com.intel.mtwilson.as.rest.v2.model.HostAttestation;
import com.intel.mtwilson.as.rest.v2.model.HostAttestationFilterCriteria;
import com.intel.mtwilson.as.rest.v2.model.HostFilterCriteria;
import com.intel.mtwilson.as.rest.v2.repository.HostAttestationRepository;
import com.intel.mtwilson.as.rest.v2.repository.HostRepository;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *
 * @author ssbangal
 */
public class HostTest {
    
    @BeforeClass 
    public static void registerPluginsForTest() {
        Extensions.register(VendorHostAgentFactory.class, VmwareHostAgentFactory.class);
        Extensions.register(VendorHostAgentFactory.class, CitrixHostAgentFactory.class);
        Extensions.register(VendorHostAgentFactory.class, IntelHostAgentFactory.class);
    }
    
    @Test
    public void testRetrieveHostAttestation() throws Exception {
        HostAttestationRepository repo = new HostAttestationRepository();
//        HostAttestationFilterCriteria criteria = new HostAttestationFilterCriteria();
//        criteria.hostUuid = UUID.valueOf("0066e3f6-c325-479a-b0ee-22d253c4369a");
        HostAttestation obj = new HostAttestation();
        obj.setHostUuid("0066e3f6-c325-479a-b0ee-22d253c4369a");
        repo.create(obj);
    }
    
}
