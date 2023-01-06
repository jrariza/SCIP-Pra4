package info.trekto.jos.core.impl.double_precision;

public class CalculateAllNewValuesRunDouble implements Runnable {

    private final SimulationLogicDouble simulation;
    private final int first;
    private final int last;
    private volatile boolean cancel = false;
    private Thread thisThread = null;

    public CalculateAllNewValuesRunDouble(SimulationLogicDouble simulation, int first, int last) {
        this.simulation = simulation;
        this.first = first;
        this.last = last;
    }

    public void cancel() {
        cancel = true;
        thisThread.interrupt();
    }

    @Override
    public void run() {
        thisThread = Thread.currentThread();
        for (int j = first; j < last && !cancel && !thisThread.isInterrupted(); j++) {
            simulation.calculateNewValues(j);
        }
    }
}
