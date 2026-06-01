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

    public OutputSchemaValidator(JsonSchemas schemas) { this.schemas = schemas; }

    public JsonNode parseAndValidate(String text, JsonNode schema) {
        JsonNode parsed = null;
        String firstError = null;
        String stage = null;

        for (Candidate cand : candidates(text)) {
            try {
                JsonNode node = mapper.readTree(cand.value());
                if (node == null || node.isMissingNode()) continue;
                parsed = node;
                stage = cand.stage();
                break;
            } catch (Exception e) {
                if (firstError == null) firstError = e.getMessage();
            }
        }

        if (parsed == null) {
            throw new SchemaViolation("parse: " + firstError);
        }
        if (!"raw".equals(stage)) {
            log.debug("output normalized via {}", stage);
        }

        var errors = schemas.validate(schema, parsed);
        if (!errors.isEmpty()) {
            var msg = errors.stream().map(Object::toString)
                    .reduce((a, b) -> a + "; " + b).orElse("");
            throw new SchemaViolation(msg);
        }
        return parsed;
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
