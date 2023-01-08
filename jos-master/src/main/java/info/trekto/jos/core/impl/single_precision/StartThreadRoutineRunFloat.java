package info.trekto.jos.core.impl.single_precision;

import info.trekto.jos.core.impl.Statistics;

import java.util.concurrent.Semaphore;

public class StartThreadRoutineRunFloat implements Runnable {
    private final int id;
    private final int numThreads;
    private final long numIters;
    private final boolean isInfiniteSim;

    private final SimulationFloat simulation;
    private final SimulationLogicFloat simulationLogic;
    private final CollisionCheckFloat collisionsCheck;

    private final int first;
    private final int last;

    private final Statistics currentIterStats; // stats of the current iteration of this thread
    private final Statistics partialStats;    // stats of the last M iterations of this thread
    private final Statistics totalStats;      // accumulated stats of all iterations of this thread

    private long startTime;
    private long finishTime;

    private volatile boolean cancel = false;
    private Thread thisThread = null;

    private static int threadsFinishedIter = 0;
    private static final Object lock = new Object();


    public StartThreadRoutineRunFloat(int id, int first, int last, SimulationFloat simFloat) {
        this.id = id;
        this.numThreads = simFloat.getProperties().getNumberOfThreads();
        this.numIters = simFloat.getProperties().getNumberOfIterations();
        this.isInfiniteSim = numIters == 0;

        this.simulation = simFloat;
        this.simulationLogic = simFloat.getSimulationLogicFloat();
        this.collisionsCheck = simFloat.getCollisionCheckKernel();
        this.first = first;
        this.last = last;

        startTime = 0;
        finishTime = 0;

        currentIterStats = new Statistics();
        partialStats = new Statistics();
        totalStats = new Statistics();
    }

    public void cancel() {
        cancel = true;
        thisThread.interrupt();
    }

    @Override
    public void run() {
        thisThread = Thread.currentThread();

        for (long i = 1; isInfiniteSim || i <= numIters; i++) {
            calculateNewValuesThread();
            checkCollisionsThread();

            partialStats.updateStats(currentIterStats);
            currentIterStats.reset();

            if (simulation.doneMIters(i)) {
                totalStats.updateStats(partialStats);
                simulation.globalStats.updateGlobalStats(partialStats);

                // Notify all threads have updated globalStats
                synchronized (simulation) {
                    simulation.finishedThreads++;
                    if (simulation.finishedThreads == numThreads)
//                        simulation.globalStats.calculateAverages(numThreads);
                    simulation.notify();
                }

                // Wait for global stats to be printed
                try {
                    simulation.globalStatsSem.acquire();
                } catch (InterruptedException e) {
                }

                printPartialStats(i);
                partialStats.reset();
                waitAllFinish(null);

                // Reset only global averages after all partial stats are printed
                if (id == 0) simulation.globalStats.resetAverages();

                printTotalStats(i);
                waitAllFinish(null);
            }
        }
    }
    private void waitForMain(Semaphore sem){
        // Wait for main notification to continue
        try {
            sem.acquire();
        } catch (InterruptedException e) {
        }
    }

    private void notifyAllThreadsFinish(Object lock){
        // Notifies the main thread that all threads have finished a certain task
        // When main can't continue if all threads haven't finished (ex: processCollision, print globalStats)
        synchronized (simulation) {
            simulation.finishedThreads++;
            if (simulation.finishedThreads == numThreads)
                simulation.notify();
        }
    }
    private void waitAllFinish(Object lock) {
        // Wait for all threads to finish
        // When thread can't continue if others threads haven't finished (ex: print statistics)
        synchronized (lock) {
            threadsFinishedIter++;
            if (threadsFinishedIter < numThreads) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                }
            } else {
                threadsFinishedIter = 0;
                lock.notifyAll();
            }
        }
    }

    private long getTimeElapsed() {
        long timeElapsed = finishTime - startTime;
        startTime = 0;
        finishTime = 0;
        return timeElapsed;
    }

    private void printPartialStats(long iter) {
        double avgPartLoad = simulation.globalStats.avgPartLoad, avgComputTime = simulation.globalStats.avgComputTime;
        double partLoadImb = (partialStats.partLoad - avgPartLoad) / avgPartLoad *100;
        double computTimeImb = (partialStats.computTime - avgComputTime) / avgComputTime*100;
        System.out.println("[" + iter + "] M Iters Thread " + id + "  [" + String.format("%04d",first) + "-" + String.format("%04d",last) + "] Statistics:" +
                "\tEvalPart: " + partialStats.evalPart +
                "\tFussPart: " + partialStats.mergedPart +
                "\tPartLoad: " + partialStats.partLoadImb + "(Desb: " + String.format("%.3f", partLoadImb) + "%)" +
                "\tCpuLoad: " + String.format("%.3f", partialStats.computTime * 10e-9) + "(Desb: " + String.format("%.3f",computTimeImb) +"%)");
    }

    private void printTotalStats(long iter) {
        System.out.println("[" + iter + "]    Total Thread " + id + "  [" + first + "-" + last + "] Statistics:" +
                "\tEvalPart: " + totalStats.evalPart +
                "\tFussPart: " + totalStats.mergedPart +
                "\tPartLoad: " + totalStats.partLoad + "(Desb: " + "ALGO" + ")" +
                "\tCpuLoad: " + String.format("%.3f", partialStats.computTime * 10e-9) + "(Desb: " + "ALGO" + ")");
    }

    private void calculateNewValuesThread() {
        long partLoad = 0, evalPart = 0;

        // Wait for notification to start calculateNewValues
        try {
            simulationLogic.calcValuesSem.acquire();
            startTime = System.nanoTime();

            for (int j = first; j < last && !cancel && !thisThread.isInterrupted(); j++) {
                evalPart += simulationLogic.calculateNewValues(j);
                if (!simulationLogic.deleted[j]) partLoad++;
            }
        } catch (InterruptedException e) {
        }
        // Notify all threads have finished calculateNewValues
        synchronized (simulationLogic) {
            simulationLogic.finishedThreads++;
            if (simulationLogic.finishedThreads == numThreads)
                simulationLogic.notify();
        }
        finishTime = System.nanoTime();

        currentIterStats.computTime+=getTimeElapsed();
        currentIterStats.evalPart+=evalPart;
        currentIterStats.partLoad+=partLoad;

    }

    private void checkCollisionsThread() {
        long partLoad = 0, mergedPart = 0;

        // Wait for notification to start checkCollisions
        try {
            collisionsCheck.collisionsSem.acquire();
            startTime = System.nanoTime();

            for (int i = first; i < last && !cancel && !thisThread.isInterrupted(); i++) {
                collisionsCheck.checkCollisions(i);
                if (!collisionsCheck.deleted[i]) partLoad++;
                if (collisionsCheck.collisions[i]) mergedPart++;
            }
        } catch (InterruptedException e) {
        }

        // Notify all threads have finished checkCollisions
        collisionsCheck.lock.lock();
        try {
            collisionsCheck.finishedThreads++;
            if (collisionsCheck.finishedThreads == numThreads)
                collisionsCheck.finishedCollisions.signalAll();
        } finally {
            collisionsCheck.lock.unlock();
        }
        finishTime = System.nanoTime();

        currentIterStats.computTime+=getTimeElapsed();
        currentIterStats.mergedPart+=mergedPart;
        currentIterStats.partLoad+=partLoad;
    }


}