package com.example.individuals.config;

import com.example.person.client.api.PersonsApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * HTTP-клиенты внешних систем. Оба строятся на автоконфигурированном {@link RestClient.Builder},
 * поэтому получают метрики и передачу trace context.
 * <p>
 * person-service вызывается через HTTP Service Client из person-client; адрес задаётся свойством
 * {@code spring.http.serviceclient.person-service.base-url}.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "person-service", types = PersonsApi.class)
public class HttpClientsConfig {

    @Bean
    RestClient keycloakRestClient(RestClient.Builder builder, KeycloakProperties properties) {
        return builder.baseUrl(properties.serverUrl()).build();
    }
}
