package info.trekto.jos.core.impl.single_precision;

import info.trekto.jos.core.impl.ConditionVar;
import info.trekto.jos.core.impl.Statistics;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

import static info.trekto.jos.util.Utils.nanoToHumanReadable;

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
                simulation.partialGlobalStats.updateStats(partialStats);
                simulation.totalGlobalStats.updateStats(partialStats);

                // notify main all stats updated
                notifyAllThreadsFinish(simulation.statsCount);
                waitForMain(simulation.statsSem1);

                // now all averages are computed
                double avgTime = simulation.statsAvgs.avgComputTime;
                double avgPartLoad = simulation.statsAvgs.avgPartLoad;
                partialStats.calculateImbalances(avgTime, avgPartLoad);

                totalStats.updateStats(partialStats);
                printPartialStats(i);

                // notify main partialSatts are printed
                notifyAllThreadsFinish(simulation.statsCount);
                waitForMain(simulation.statsSem2);

                printTotalStats(i);
                partialStats.reset();
                waitAllFinish(simulation.statsCount);
            }
        }
    }

    private void waitForMain(Semaphore sem) {
        // Wait for main notification to continue
        try {
            sem.acquire();
        } catch (InterruptedException e) {
        }
    }

    private void notifyAllThreadsFinish(ConditionVar counter) {
        // Notifies the main thread that all threads have finished a certain task
        // When main can't continue if all threads haven't finished (ex: processCollision, print globalStats)
        synchronized (counter) {
            counter.finishedThreads++;
            if (counter.finishedThreads == numThreads)
                counter.notify();
        }
    }

    private void notifyAllThreadsFinishLock(Lock lock, Condition finished, ConditionVar counter) {
        lock.lock();
        try {
            counter.finishedThreads++;
            if (counter.finishedThreads == numThreads)
                finished.signalAll();
        } finally {
            lock.unlock();
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
        double partLoadImb = partialStats.partLoadImb * 100;
        double computTimeImb = partialStats.timeImb * 100;
        System.out.println("[" + iter + "] M Iters Thread " + id + "  [" + String.format("%04d", first) + "-" + String.format("%04d", last) + "] Statistics:" +
                "\tEvalPart: " + String.format("%,12d",partialStats.evalPart) +
                "\tFussPart: " + String.format("%,6d",partialStats.mergedPart) +
                "\tPartLoad: " + String.format("%,12d",partialStats.partLoad) + " (Desb: " + String.format("%.3f", partLoadImb) + "%)" +
                "\tCpuLoad: " + String.format("%12s",nanoToHumanReadable(partialStats.computTime)) + " (Desb: " + String.format("%.3f", computTimeImb) + "%)");
    }

    private void printTotalStats(long iter) {
        double partLoadImb = totalStats.partLoadImb * 100;
        double computTimeImb = totalStats.timeImb * 100;
        System.out.println("[" + iter + "]   Total Thread " + id + "  [" + String.format("%04d",first) + "-" + String.format("%04d",last) + "] Statistics:" +
                "\tEvalPart: " + String.format("%,12d",totalStats.evalPart) +
                "\tFussPart: " + String.format("%,6d",totalStats.mergedPart) +
                "\tPartLoad: " + String.format("%,12d",totalStats.partLoad) + " (Desb: " + String.format("%.3f", partLoadImb) + "%)" +
                "\tCpuLoad: " + String.format("%12s",nanoToHumanReadable(totalStats.computTime)) + " (Desb: " + String.format("%.3f", computTimeImb) + "%)");
    }

    private void calculateNewValuesThread() {
        // Wait for notification to start calculateNewValues
        waitForMain(simulationLogic.calcValuesSem);
        startTime = System.nanoTime();

        long partLoad = 0, evalPart = 0;
        for (int j = first; j < last && !cancel && !thisThread.isInterrupted(); j++) {
            evalPart += simulationLogic.calculateNewValues(j);
            if (!simulationLogic.deleted[j]) partLoad++;
        }

        finishTime = System.nanoTime();

        // Notify all threads have finished calculateNewValues
        notifyAllThreadsFinish(simulationLogic.calcCount);

        currentIterStats.computTime += getTimeElapsed();
        currentIterStats.evalPart += evalPart;
        currentIterStats.partLoad += partLoad;
    }

    private void checkCollisionsThread() {
        // Wait for notification to start checkCollisions
        waitForMain(collisionsCheck.collisionsSem);
        startTime = System.nanoTime();

        long partLoad = 0, mergedPart = 0;
        for (int i = first; i < last && !cancel && !thisThread.isInterrupted(); i++) {
            collisionsCheck.checkCollisions(i);
            if (!collisionsCheck.deleted[i]) partLoad++;
            if (collisionsCheck.collisions[i]) mergedPart++;
        }

        finishTime = System.nanoTime();

        // Notify all threads have finished checkCollisions
        notifyAllThreadsFinishLock(collisionsCheck.collLock,
                collisionsCheck.finishedCollisions, collisionsCheck.collCount);

        currentIterStats.computTime += getTimeElapsed();
        currentIterStats.mergedPart += mergedPart;
        currentIterStats.partLoad += partLoad;
    }


}