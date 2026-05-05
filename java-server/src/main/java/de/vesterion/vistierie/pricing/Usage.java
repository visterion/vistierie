package de.vesterion.vistierie.pricing;

public record Usage(int inputTokens, int outputTokens,
                    int cacheCreationInputTokens, int cacheReadInputTokens) {}
