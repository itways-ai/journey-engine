package com.itways.assistant.journey.engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ResourceLoader;
import org.springframework.ui.freemarker.FreeMarkerConfigurationFactoryBean;

import com.itways.assistant.ai.EnableAi;

import freemarker.template.TemplateExceptionHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@EnableAi
@Configuration
@ComponentScan("com.itways.assistant.journey.engine")
public class JourneyConfiguration {

	@PostConstruct
	public void print() {
		log.info("✅ Journey Engine SDK configuration initialized");
	}

//	@Primary
	@Bean(name = "sdkRenderConfig")
	public freemarker.template.Configuration sdkRenderConfig() throws Exception {
		FreeMarkerConfigurationFactoryBean factoryBean = new FreeMarkerConfigurationFactoryBean();
		factoryBean.setTemplateLoaderPath("classpath:/");
		factoryBean.setDefaultEncoding("UTF-8");

		freemarker.template.Configuration configuration = factoryBean.createConfiguration();

		// Handle missing parameters gracefully by skipping them
		configuration.setTemplateExceptionHandler(TemplateExceptionHandler.IGNORE_HANDLER);
		configuration.setLogTemplateExceptions(false);
		configuration.setWrapUncheckedExceptions(true);
		configuration.setFallbackOnNullLoopVariable(false);
		configuration.setClassicCompatible(true);

		return configuration;
	}

}
