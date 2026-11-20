package ml.mypals.microtimingreplay.record;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A recording clock advances with simulation, never with idle server loops. */
public final class RecordingClock {
    private record Position(int dimension, int phase) implements Comparable<Position> {
        @Override public int compareTo(Position other) {
            int dimensionOrder = Integer.compare(dimension, other.dimension);
            return dimensionOrder != 0 ? dimensionOrder : Integer.compare(phase, other.phase);
        }
    }

    private final long baseTick;
    private final int targetTicks;
    private final Map<String, Integer> dimensions = new LinkedHashMap<>();
    private final int phaseCount;
    private int completedTicks;
    private boolean subTick;
    private boolean vanillaTick;
    private boolean subTickPhase;
    private boolean completedLastPhase;
    private boolean progressed;
    private Position anchor;
    private Position previous;
    private long activity;

    public RecordingClock(long baseTick, int targetTicks, List<String> dimensionPaths,
                          int phaseCount, String frozenDimension, int frozenPhase) {
        this.baseTick = baseTick;
        this.targetTicks = targetTicks;
        this.phaseCount = phaseCount;
        for (String path : dimensionPaths) dimensions.putIfAbsent(path, dimensions.size());
        if (frozenDimension != null) {
            subTick = true;
            anchor = position(frozenDimension, frozenPhase);
        }
    }

    public void beginServerTick(boolean runsNormally) {
        vanillaTick = !subTick && runsNormally;
        completedLastPhase = false;
        subTickPhase = false;
        if (vanillaTick) activity++;
    }

    public boolean needsSubTickAnchor() { return anchor == null; }

    public boolean couldCompleteAt(String dimension, int phase) {
        return progressed && anchor != null && crosses(previous, position(dimension, phase));
    }

    /** Called before the phase body, after SubTick has accepted or rejected it. */
    public boolean observeSubTickPhase(String dimension, int phase, boolean allowed, boolean freezePoint) {
        subTick = true;
        vanillaTick = false;
        subTickPhase = allowed;
        if (finished() || (!allowed && !freezePoint)) return false;
        Position position = position(dimension, phase);
        if (anchor == null) anchor = position;
        boolean boundary = progressed && crosses(previous, position);
        if (boundary) completedTicks++;
        subTickPhase = allowed && !finished();
        if (allowed && !finished()) {
            previous = position;
            progressed = true;
            activity++;
            completedLastPhase = position.dimension == dimensions.size() - 1
                    && phaseCount > 0 && phase == phaseCount - 1;
        } else if (freezePoint && boundary) {
            previous = position;
            progressed = false;
        }
        return boundary;
    }

    /** The last phase body must finish before a tick-boundary anchor is settled. */
    public boolean endServerTick() {
        if (finished()) return false;
        if (subTick) {
            if (!completedLastPhase || !progressed || anchor == null
                    || anchor.dimension != 0 || anchor.phase != 0) return false;
            completedTicks++;
            previous = anchor;
            progressed = false;
            completedLastPhase = false;
            subTickPhase = false;
            return true;
        }
        if (!vanillaTick) return false;
        vanillaTick = false;
        completedTicks++;
        return true;
    }

    private Position position(String dimension, int phase) {
        return new Position(dimensions.computeIfAbsent(dimension, ignored -> dimensions.size()), phase);
    }

    private boolean crosses(Position from, Position to) {
        int order = from.compareTo(to);
        if (order < 0) return from.compareTo(anchor) < 0 && anchor.compareTo(to) <= 0;
        if (order > 0) return from.compareTo(anchor) < 0 || anchor.compareTo(to) <= 0;
        return false;
    }

    public boolean finished() { return completedTicks >= targetTicks; }
    public long currentTick() { return baseTick + completedTicks + (finished() ? 0 : 1); }
    public long completedTick() { return baseTick + completedTicks; }
    public long remainingTicks() { return Math.max(0, targetTicks - completedTicks); }
    public long baseTick() { return baseTick; }
    public long activity() { return activity; }
    public boolean simulationRunning() { return vanillaTick || subTickPhase; }
    public boolean subTickPhaseRunning() { return subTickPhase; }
}
