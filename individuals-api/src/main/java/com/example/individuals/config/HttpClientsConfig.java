package com.example.individuals.config;

import com.example.person.client.api.PersonsApi;
import org.springframework.boot.http.client.autoconfigure.reactive.ClientHttpConnectorBuilderCustomizer;
import org.springframework.boot.http.client.reactive.ReactorClientHttpConnectorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * HTTP-клиенты внешних систем. Оба строятся на автоконфигурированном {@link WebClient.Builder},
 * поэтому получают метрики и передачу trace context.
 * <p>
 * person-service вызывается через HTTP Service Client из person-client; адрес задаётся свойством
 * {@code spring.http.serviceclient.person-service.base-url}.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "person-service", types = PersonsApi.class, clientType = HttpServiceGroup.ClientType.WEB_CLIENT)
public class HttpClientsConfig {

    @Bean
    WebClient keycloakWebClient(WebClient.Builder builder, KeycloakProperties properties) {
        return builder.baseUrl(properties.serverUrl()).build();
    }

    /**
     * Сжатие ответов выключено: ответы внешних систем маленькие, а распаковка gzip в Netty 4.2
     * на JDK 25 падает с {@code UnsupportedOperationException} (ByteBuffer из shared arena).
     */
    @Bean
    ClientHttpConnectorBuilderCustomizer<ReactorClientHttpConnectorBuilder> disableResponseCompression() {
        return builder -> builder.withHttpClientCustomizer(client -> client.compress(false));
    }
}
