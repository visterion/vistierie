package de.vesterion.vistierie.runs;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class LongPollService {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Runnable>> waiters = new ConcurrentHashMap<>();

    public void register(String runId, Runnable wakeup) {
        waiters.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(wakeup);
    }

    public void unregister(String runId, Runnable wakeup) {
        var list = waiters.get(runId);
        if (list != null) list.remove(wakeup);
    }

    public void notifyTerminal(String runId) {
        var list = waiters.remove(runId);
        if (list == null) return;
        for (Runnable r : list) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }
}
