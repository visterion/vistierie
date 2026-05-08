package de.vesterion.vistierie.testsupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class StubLlmScripts {

    public record ScriptedTurn(String stopReason, String text, List<ScriptedToolUse> toolUses) {}
    public record ScriptedToolUse(String id, String name, Map<String, Object> input) {}

    public static class Turn {
        public static ScriptedTurn text(String t) { return new ScriptedTurn("end_turn", t, List.of()); }
        public static ScriptedTurn endTurn(String t) { return new ScriptedTurn("end_turn", t, List.of()); }
        public static ScriptedTurn toolUses(ScriptedToolUse... tus) {
            return new ScriptedTurn("tool_use", null, List.of(tus));
        }
        public static ScriptedToolUse toolUse(String name, Map<String, Object> input) {
            return new ScriptedToolUse("toolu_" + UUID.randomUUID(), name, input);
        }
    }

    private StubLlmScripts() {}
}
