package com.pixframe.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the existing frontend assets (pixframe/static/) directly from disk
 * at the same /static/... URLs Flask used, so pixframe/js, webpack.config.js,
 * and the built bundle.js don't need to move or change.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.static-dir}")
    private String staticDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/static/**")
                .addResourceLocations("file:" + staticDir + "/");
    }
}
