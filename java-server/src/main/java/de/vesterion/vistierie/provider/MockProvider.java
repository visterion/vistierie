package de.vesterion.vistierie.provider;

import de.vesterion.vistierie.pricing.Usage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "vistierie.mock-llm", havingValue = "true")
public class MockProvider implements LlmProvider {
    @Override public String name() { return "anthropic"; }
    @Override public ProviderResponse complete(ProviderRequest req) {
        return new ProviderResponse("[mock] " + summarize(req), "end_turn",
                new Usage(42, 7, 0, 0), req.model());
    }
    @Override public ProviderResponse vision(String model, int maxTokens,
                                              String mediaType, String base64, String prompt) {
        return new ProviderResponse("[mock vision] " + prompt, "end_turn",
                new Usage(120, 10, 0, 0), model);
    }
    @Override public ProviderResponse visionMulti(String model, int maxTokens,
                                                  java.util.List<ImageInput> images, String prompt) {
        return new ProviderResponse("[mock vision-multi " + images.size() + "] " + prompt, "end_turn",
                new de.vesterion.vistierie.pricing.Usage(120, 10, 0, 0), model);
    }
    private static String summarize(ProviderRequest req) {
        return req.messages().isEmpty() ? "" :
                String.valueOf(req.messages().get(req.messages().size() - 1).get("content"));
    }
}
