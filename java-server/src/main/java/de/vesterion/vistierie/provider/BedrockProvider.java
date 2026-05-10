package de.vesterion.vistierie.provider;

import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

public class BedrockProvider implements LlmProvider {

    private final BedrockRuntimeClient client;

    BedrockProvider(BedrockRuntimeClient client) {
        this.client = client;
    }

    @Override
    public String name() { return "bedrock"; }

    @Override
    public ProviderResponse complete(ProviderRequest req) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public ProviderResponse vision(String model, int maxTokens,
                                   String mediaType, String base64, String prompt) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
