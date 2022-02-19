package com.yls.activiti7demo.config;

import org.springdoc.core.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author joe 2022-02-19 09:02
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public GroupedOpenApi api() {
        return GroupedOpenApi.builder()
                .group("activivi-demo")
                .pathsToMatch("/**")
                // .addOpenApiMethodFilter(method -> method.isAnnotationPresent(Operation.class))
                .build();
    }
}
