package com.oraclereplicator.replicator.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.vault.config.VaultProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.vault.annotation.VaultPropertySource;
import org.springframework.vault.authentication.AppRoleAuthentication;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions;
import org.springframework.vault.authentication.ClientAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

@Configuration
@VaultPropertySource("secret_v2_t/data/ord/src/connections")
public class VaultConfig {
    
    @Bean
    @ConfigurationProperties("spring.cloud.vault")
    public VaultProperties vaultProperties() {
        return new VaultProperties();
    }
    
    @Bean
    public VaultTemplate vaultTemplate(VaultProperties vaultProperties) {
        VaultEndpoint vaultEndpoint = VaultEndpoint.from(URI.create(vaultProperties.getUri()));
        
        // Настройка AppRole аутентификации
        AppRoleAuthenticationOptions appRoleOptions = AppRoleAuthenticationOptions.builder()
                .roleId(AppRoleAuthenticationOptions.RoleId.provided(vaultProperties.getAppRole().getRoleId()))
                .secretId(AppRoleAuthenticationOptions.SecretId.provided(vaultProperties.getAppRole().getSecretId()))
                .build();
        
        ClientAuthentication clientAuthentication = new AppRoleAuthentication(appRoleOptions, 
                restOperations(vaultProperties));
        
        VaultTemplate vaultTemplate = new VaultTemplate(vaultEndpoint, clientAuthentication);
        return vaultTemplate;
    }
    
    private RestOperations restOperations(VaultProperties vaultProperties) {
        RestTemplate restTemplate = new RestTemplate();
        
        // Настройка таймаутов
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(vaultProperties.getConnectionTimeout());
        factory.setReadTimeout(vaultProperties.getReadTimeout());
        restTemplate.setRequestFactory(factory);
        
        return restTemplate;
    }
}
