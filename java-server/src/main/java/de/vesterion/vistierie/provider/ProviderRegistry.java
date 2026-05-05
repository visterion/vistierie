package de.vesterion.vistierie.provider;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ProviderRegistry {
    private final Map<String, LlmProvider> byName;
    public ProviderRegistry(List<LlmProvider> providers) {
        this.byName = providers.stream().collect(Collectors.toMap(LlmProvider::name, p -> p));
    }
    public LlmProvider get(String name) {
        var p = byName.get(name);
        if (p == null) throw new IllegalArgumentException("unknown provider " + name);
        return p;
    }
}
