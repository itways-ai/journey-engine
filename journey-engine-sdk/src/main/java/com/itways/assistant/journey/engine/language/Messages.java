package com.itways.assistant.journey.engine.language;

import java.util.Locale;

import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * A locale-keyed string table for one module's user-facing text.
 *
 * <p>
 * Wraps Spring's {@link ResourceBundleMessageSource} rather than exposing it as
 * a bean: the host applications already define their own {@code messageSource}
 * for unrelated purposes, and adding a second one is how a module's strings
 * quietly start resolving out of somebody else's bundle. Each module builds its
 * own instance over its own basename instead, so ownership of a key is obvious
 * from the class that reads it.
 *
 * <p>
 * A missing key returns the key itself rather than throwing. A wrong-looking
 * string in a chat window is a bug report; an exception mid-journey is a failed
 * run, and the two are not equally bad.
 */
public class Messages {

    private final ResourceBundleMessageSource source;

    protected Messages(String basename) {
        ResourceBundleMessageSource bundle = new ResourceBundleMessageSource();
        bundle.setBasename(basename);
        // Explicit despite Java 9+ defaulting properties files to UTF-8: the
        // Arabic bundle is unreadable if anything in the chain falls back to the
        // platform encoding, and it fails as mojibake rather than as an error.
        bundle.setDefaultEncoding("UTF-8");
        // Fall back to the base bundle, not to the JVM's default locale. A server
        // running under an unrelated locale must not change what users are told.
        bundle.setFallbackToSystemLocale(false);
        bundle.setUseCodeAsDefaultMessage(true);
        this.source = bundle;
    }

    public String get(ConversationLanguage language, String key, Object... args) {
        Locale locale = (language != null ? language : ConversationLanguage.DEFAULT).toLocale();
        return source.getMessage(key, args, locale);
    }
}
