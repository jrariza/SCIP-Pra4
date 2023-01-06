package info.trekto.jos.core.impl.single_precision;

public class CalculateAllNewValuesRunFloat implements Runnable {

    private final SimulationLogicFloat simulation;
    private final int first;
    private final int last;
    private volatile boolean cancel = false;
    private Thread thisThread = null;

    public CalculateAllNewValuesRunFloat(SimulationLogicFloat simulation, int first, int last) {
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
