package de.vesterion.vistierie.agent.runner;

public class RecursionGuard {

    public static class DepthExceeded extends RuntimeException {
        public DepthExceeded(int max) { super("subagent depth exceeded: max=" + max); }
    }

    private final int max;
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    public RecursionGuard(int max) { this.max = max; }

    public void enter() {
        int d = depth.get();
        if (d >= max) throw new DepthExceeded(max);
        depth.set(d + 1);
    }
    public void exit() {
        int d = depth.get();
        if (d > 0) depth.set(d - 1);
    }
    public int depth() { return depth.get(); }
}
