package de.vesterion.vistierie.agent.runner;

/**
 * Stateless subagent recursion-depth guard. Depth is propagated explicitly through the run
 * chain (captured in the subagent spawn lambda's closure) rather than tracked per-thread,
 * so the limit survives the async subagent boundary where each subagent runs on a fresh
 * virtual thread.
 */
public class RecursionGuard {

    public static class DepthExceeded extends RuntimeException {
        public DepthExceeded(int max) { super("subagent depth exceeded: max=" + max); }
    }

    private final int max;

    public RecursionGuard(int max) { this.max = max; }

    /** Throws {@link DepthExceeded} when {@code depth} exceeds the configured maximum. */
    public void check(int depth) {
        if (depth > max) throw new DepthExceeded(max);
    }
}
