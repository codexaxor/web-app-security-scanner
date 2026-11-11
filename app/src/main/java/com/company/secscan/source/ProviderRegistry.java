package com.company.secscan.source;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link SourceProvider} adapter for a provider id ({@code gitlab}, {@code github}). Collects every
 * {@link SourceProvider} bean on the classpath, so adding a provider is just adding a bean (M8.7 item 2).
 */
@Component
public class ProviderRegistry {

    private final Map<String, SourceProvider> byId;

    public ProviderRegistry(List<SourceProvider> providers) {
        this.byId = providers.stream().collect(Collectors.toMap(SourceProvider::id, Function.identity()));
    }

    /** The adapter for {@code providerId}, or throws {@link IllegalArgumentException} if none is registered. */
    public SourceProvider require(String providerId) {
        SourceProvider p = byId.get(providerId);
        if (p == null) {
            throw new IllegalArgumentException("No source provider for '" + providerId + "'.");
        }
        return p;
    }

    public boolean has(String providerId) {
        return byId.containsKey(providerId);
    }
}
