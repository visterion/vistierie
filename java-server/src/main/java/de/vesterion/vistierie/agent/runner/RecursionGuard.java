package de.vesterion.vistierie.agent.runner;

public class RecursionGuard {

    public static class DepthExceeded extends RuntimeException {
        public DepthExceeded(int max) { super("subagent depth exceeded: max=" + max); }
    }

    private final int max;
    private int depth;

    public RecursionGuard(int max) { this.max = max; }

    public void enter() {
        if (depth >= max) throw new DepthExceeded(max);
        depth++;
    }
    public void exit() { if (depth > 0) depth--; }
    public int depth() { return depth; }
}
