
package com.intel.mtwilson.as.rest;


import javax.ejb.Stateless;
import javax.ws.rs.Consumes;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.GET;
/**
 * REST Web Service
 * * 
 */

@Stateless
@Path("/status")
public class AsStatus {
    @GET
    @Produces({MediaType.TEXT_PLAIN})
    public String getServiceStatus() {
        return "AS Service Running 1.2.2 premium ";
    }
}
