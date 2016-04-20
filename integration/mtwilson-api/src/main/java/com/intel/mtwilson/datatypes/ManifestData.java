package com.intel.mtwilson.datatypes;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import com.fasterxml.jackson.annotation.JsonProperty;
//import org.codehaus.jackson.annotate.JsonProperty;

/**
 *
 * @author dsmagadx
 */
@XmlRootElement
@XmlAccessorType(XmlAccessType.PROPERTY)
public class ManifestData {

    private String name;
    private String value;

    private ManifestData() {
    }

    public ManifestData(String name, String value) {
        setName(name);
        setValue(value);
    }

    @JsonProperty("Name")
    public final void setName(String name) {
        this.name = name;
    }

    @JsonProperty("Name")
    public String getName() {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("PCR Name is missing");
        }
        return name;
    }

    @JsonProperty("Value")
    public final void setValue(String value) {
        this.value = value;
    }

    @JsonProperty("Value")
    public String getValue() {
        if (value == null || value.isEmpty()) {
            return "";
            // throw new IllegalArgumentException("PCR Value is missing");
        }
        return value;
    }
}
