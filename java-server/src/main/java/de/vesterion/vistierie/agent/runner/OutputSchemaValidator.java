package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.agents.JsonSchemas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OutputSchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(OutputSchemaValidator.class);

    public static class SchemaViolation extends RuntimeException {
        public SchemaViolation(String m) { super(m); }
    }

    /** First markdown fenced block: ```lang\n ... ``` (DOTALL, non-greedy). */
    private static final Pattern FENCE =
            Pattern.compile("```[\\w.+-]*\\s*\\n?(.*?)```", Pattern.DOTALL);

    private final JsonSchemas schemas;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Above every stage index, so a schema error always outranks a parse error. */
    private static final int SCHEMA_RANK = Integer.MAX_VALUE;

    public OutputSchemaValidator(JsonSchemas schemas) { this.schemas = schemas; }

    public JsonNode parseAndValidate(String text, JsonNode schema) {
        // Rank of the furthest candidate seen. A schema error (parsed, failed validation)
        // always outranks a parse error; among parse errors a later stage outranks an
        // earlier one. Reporting the FIRST error instead sent a real diagnosis down the
        // wrong path: the raw candidate's "Unrecognized token 'Alle'" hid a delimiter
        // break inside the fenced JSON (gropar, 2026-08-10/11).
        int bestRank = -1;
        String bestMessage = null;

        int stageIndex = 0;
        for (Candidate cand : candidates(text)) {
            int parseRank = stageIndex++;
            JsonNode node;
            try {
                node = mapper.readTree(cand.value());
            } catch (Exception e) {
                if (parseRank > bestRank) {
                    bestRank = parseRank;
                    bestMessage = cand.stage() + ": " + e.getMessage();
                }
                continue;
            }
            if (node == null || node.isMissingNode()) continue;

            var errors = schemas.validate(schema, node);
            if (errors.isEmpty()) {
                if (!"raw".equals(cand.stage())) {
                    log.debug("output normalized via {}", cand.stage());
                }
                return node;
            }
            // Any schema error outranks every parse error: SCHEMA_RANK is above all stages.
            if (SCHEMA_RANK > bestRank) {
                bestRank = SCHEMA_RANK;
                bestMessage = cand.stage() + ": " + errors.stream().map(Object::toString)
                        .reduce((a, b) -> a + "; " + b).orElse("");
            }
        }

        throw new SchemaViolation(bestMessage == null ? "no output candidates" : bestMessage);
    }

    private record Candidate(String stage, String value) {}

    /** Ordered candidates: raw text, first fenced block content, then every top-level balanced value. */
    private static List<Candidate> candidates(String text) {
        List<Candidate> out = new ArrayList<>();
        if (text == null) return out;
        out.add(new Candidate("raw", text));
        Matcher m = FENCE.matcher(text);
        if (m.find()) out.add(new Candidate("fence-strip", m.group(1).strip()));
        for (String v : balancedValues(text)) out.add(new Candidate("balanced-extract", v));
        return out;
    }

    /** Every top-level balanced JSON value ({...} or [...]) in order; string/escape aware. */
    private static List<String> balancedValues(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{' || c == '[') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}' || c == ']') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        out.add(text.substring(start, i + 1));
                        start = -1;
                    }
                }
            }
        }
        return out;
    }
}
