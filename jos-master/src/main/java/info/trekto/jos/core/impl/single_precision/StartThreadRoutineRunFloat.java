package info.trekto.jos.core.impl.single_precision;

import info.trekto.jos.core.impl.Statistics;

public class StartThreadRoutineRunFloat implements Runnable {
    private final int numThreads;
    private final long numIters;
//    private final int M;
    private final boolean isInfiniteSim;

    private final SimulationLogicFloat simulation;
    private final CollisionCheckFloat collisions;

    private final int first;
    private final int last;
    private Statistics partialStats; // stats of the last M iterations
    private Statistics totalStats;   // accumulated stats of all iterations

    private volatile boolean cancel = false;
    private Thread thisThread = null;

    public StartThreadRoutineRunFloat(int numThreads, long numIters, boolean isInfinite, SimulationLogicFloat simulation, CollisionCheckFloat collisions, int first, int last) {
        this.numThreads = numThreads;
        this.numIters = numIters;
        this.isInfiniteSim = isInfinite;

        this.simulation = simulation;
        this.collisions = collisions;
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
        for (long i=0; isInfiniteSim || i< numIters; i++){
            System.out.println("threadbegin");
            calculateNewValuesThread();
            checkCollisionsThread();

        }
    }

    private void calculateNewValuesThread() {
        // Wait for notification to start calculateNewValues
        try {
            simulation.calcValuesSem.acquire();
            for (int j = first; j < last && !cancel && !thisThread.isInterrupted(); j++)
                simulation.calculateNewValues(j);
            System.out.println("calc");
        } catch (InterruptedException e) {
        }

        // Notify all threads have finished calculateNewValues
        synchronized (simulation) {
            simulation.finishedThreads++;
            if (simulation.finishedThreads == numThreads)
                simulation.notify();
        }
    }

    private void checkCollisionsThread() {
        // Wait for notification to start checkCollisions
        try {
            collisions.collisionsSem.acquire();
            for (int i = first; i < last && !cancel && !thisThread.isInterrupted(); i++)
                collisions.checkCollisions(i);
            System.out.println("checkcoll");
        } catch (InterruptedException e) {
        }

        // Notify all threads have finished checkCollisions
        collisions.lock.lock();
        try {
            collisions.finishedThreads++;
            if (collisions.finishedThreads == numThreads)
                collisions.finishedCollisions.signalAll();
        } finally {
            collisions.lock.unlock();
        }
    }


}