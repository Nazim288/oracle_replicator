package com.gpb.replication.oracle.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
@Setter
public class SourceDbConnections implements Serializable {
    private String name;
    @JsonProperty("service_name")
    private String serviceName;
    @JsonProperty("db_type")
    private String dbType;
    private List<String> url;
    private String username;
    private String password;

    @JsonIgnore
    public String getHostFromUrl() {
        if (url == null) return "unknown-host";
        try {
            String urlFirst = url.get(0);
            Pattern pattern = Pattern.compile("@/?/?([^:/]+)");
            Matcher matcher = pattern.matcher(urlFirst);
            return matcher.find() ? matcher.group(1) : "unknown-host";
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    @JsonIgnore
    public int getPortFromUrl() {
        if (url == null) return -1;
        try {
            String urlFirst = url.get(0);
            Pattern pattern = Pattern.compile(":(\\d+)");
            Matcher matcher = pattern.matcher(urlFirst);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : 1521;
        } catch (Exception e) {
            return 1521;
        }
    }
}
