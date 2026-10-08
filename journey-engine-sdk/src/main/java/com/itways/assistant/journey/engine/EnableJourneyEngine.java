package com.itways.assistant.journey.engine;

import com.itways.assistant.journey.engine.config.JourneyConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(JourneyConfiguration.class)
public @interface EnableJourneyEngine {
}
