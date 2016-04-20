/*
 * Copyright (C) 2013 Intel Corporation
 * All rights reserved.
 */
package com.intel.mtwilson;

import com.intel.mtwilson.crypto.Aes128;
import com.intel.dcsg.cpg.crypto.CryptographyException;
import com.intel.mtwilson.api.MtWilson;
import com.intel.mtwilson.fs.ApplicationFilesystem;
import com.intel.mtwilson.util.ASDataCipher;
import com.intel.mtwilson.util.Aes128DataCipher;
import java.io.IOException;
import java.net.MalformedURLException;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Convenience class for instantiating an API CLIENT for your unit tests.  Relies on MyConfiguration for
 * your local settings.
 * 
 * How to use it in your code:
 * 
 * ApiClient client = My.client();
 * 
 * @author jbuhacoff
 */
public class My {
    private transient static Logger log = LoggerFactory.getLogger(My.class);
    private static MyConfiguration config = null;
//    private static MtWilson client = null;
    private static MyClient client = null;
    private static MyPersistenceManager pm = null;
    private static MyJdbc jdbc = null;
    private static MyJpa jpa = null;
    private static MyEnvironment env = null;
//    private static MyLocale locale = null;

    public static void initDataEncryptionKey() throws IOException {
        initDataEncryptionKey(My.configuration().getDataEncryptionKeyBase64());
    }

    public static void initDataEncryptionKey(String dekBase64) {
        try {
            //log.info("DEK = {}", dekBase64);
            ASDataCipher.cipher = new Aes128DataCipher(new Aes128(Base64.decodeBase64(dekBase64)));
            //log.info("My ASDataCipher ref = {}", ASDataCipher.cipher.hashCode());
        }
        catch(CryptographyException e) {
            throw new IllegalArgumentException("Cannot initialize data encryption cipher", e);
        }              
    }

    public static void init() throws IOException {
        initDataEncryptionKey();
    }
    
    public static void reset() { config = null; jpa = null; }
    
    public static MyConfiguration configuration() { 
        if( config == null ) {
             config = new MyConfiguration();
        }
        return config; 
    }
    
    public static MtWilson client() throws MalformedURLException, IOException {
        if( client == null ) {
            client = new MyClient();
        }
        return client.v1();
    }
    
    public static MyPersistenceManager persistenceManager() throws IOException {
        if( pm == null ) {
            pm = new MyPersistenceManager(configuration().getProperties(
                    "mtwilson.db.protocol", "mtwilson.db.driver",
                    "mtwilson.db.host", "mtwilson.db.port", "mtwilson.db.user", 
                    "mtwilson.db.password", "mtwilson.db.schema", "mtwilson.as.dek"));
        }
        return pm;
    }
    
    public static MyJdbc jdbc() throws IOException {
        if( jdbc == null ) {
            jdbc = new MyJdbc(configuration());
        }
        return jdbc;
    }
    
    public static MyJpa jpa() throws IOException {
        if( jpa == null ) {
            initDataEncryptionKey();
            jpa = new MyJpa(persistenceManager());
        }
        return jpa;
    }
    
    public static MyEnvironment env() throws IOException {
        if( env == null ) {
            env = new MyEnvironment(configuration().getEnvironmentFile());
        }
        return env;
    }
    
    /*
    public static MyLocale locale() throws IOException {
        if( locale == null ) {
            locale = new MyLocale(configuration().getLocale());
        }
        return locale;
    }
    */
    
    public static ApplicationFilesystem filesystem() {
        return MyFilesystem.getApplicationFilesystem();
    }
}
