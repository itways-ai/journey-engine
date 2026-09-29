package com.itways.assistant.journey.engine.handler;

import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * FreeMarker rendering inside the engine: DATA_MAP builds its prompt with
 * {@link #renderFromString}. File templates are read with the engine's own
 * {@code sdkRenderConfig} bean (missing values skipped, classic compatible).
 *
 * <p>
 * common-lib has a near-identical {@code com.itways.freemarker.TemplateRender}.
 * The engine keeps this copy on purpose: the SDK must not depend on
 * common-lib's web/messaging parts, which is where that helper lives, and this
 * copy is bound to the engine's configuration rather than the host's primary
 * FreeMarker {@code Configuration}. Internal to the engine, not for hosts;
 * stored templates are rendered through {@code TemplateRenderPort}
 * (TEMPLATE_RENDER).
 */
@Slf4j
@Component
public class TemplateRender {

	private final Configuration sdkRenderConfig;
	private final ResourceLoader resourceLoader;

	public TemplateRender(@Qualifier("sdkRenderConfig") Configuration sdkRenderConfig, ResourceLoader resourceLoader) {

		this.sdkRenderConfig = sdkRenderConfig;
		this.resourceLoader = resourceLoader;
	}

	/**
	 * Renders a template from a file path (relative to classpath:/templates/).
	 */
	public String renderFromFile(String templatePath, Map<String, Object> model) {
		try {
			// First try standard FreeMarker loading (which uses the configured
			// TemplateLoader)
			Template template = sdkRenderConfig.getTemplate(templatePath);
			return FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
		} catch (Exception e) {
			log.warn("Template not found via standard loader: {}. Attempting ResourceLoader fallback...", templatePath);
			try {
				// Fallback: Use Spring's ResourceLoader to find the template (handles
				// JARs/Classpath better)
				String fullPath = templatePath.startsWith("classpath:") ? templatePath : "classpath:/" + templatePath;
				Resource resource = resourceLoader.getResource(fullPath);

				if (resource.exists()) {
					Template template = new Template(templatePath,
							new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8), sdkRenderConfig);
					return FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
				}
			} catch (Exception ex) {
				log.error("ResourceLoader fallback failed for: {}", templatePath, ex);
			}

			log.error("Error rendering template from file: {}", templatePath, e);
			throw new RuntimeException("Failed to render template from file: " + templatePath, e);
		}
	}

	/**
	 * Renders a template from a raw string content.
	 */
	public String renderFromString(String templateContent, Map<String, Object> model) {
		try {
			// We use a temporary configuration or a string loader to avoid polluting the
			// global config
			Configuration stringConfig = new Configuration(Configuration.VERSION_2_3_32);
			stringConfig.setTemplateExceptionHandler(sdkRenderConfig.getTemplateExceptionHandler());

			StringTemplateLoader stringLoader = new StringTemplateLoader();
			stringLoader.putTemplate("dynamicTemplate", templateContent);
			stringConfig.setTemplateLoader(stringLoader);

			Template template = stringConfig.getTemplate("dynamicTemplate");
			return FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
		} catch (Exception e) {
			log.error("Error rendering template from string", e);
			throw new RuntimeException("Failed to render template from string", e);
		}
	}
}
