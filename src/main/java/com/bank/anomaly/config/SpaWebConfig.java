package com.bank.anomaly.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Forwards unknown paths to index.html so that the Flutter SPA
 * can handle client-side routing (e.g. /review-queue, /analytics, /settings).
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource resource = location.createRelative(resourcePath);
                        if (resource.exists() && resource.isReadable()) {
                            return resource;
                        }
                        // Fallback to index.html for SPA routing — but not for API or actuator paths
                        if (!resourcePath.startsWith("api/") && !resourcePath.startsWith("actuator/")
                                && !resourcePath.startsWith("v3/") && !resourcePath.startsWith("swagger")) {
                            return new ClassPathResource("/static/index.html");
                        }
                        return null;
                    }
                });
    }
}
