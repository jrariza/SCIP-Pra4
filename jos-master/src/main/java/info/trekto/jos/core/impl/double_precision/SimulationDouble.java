package info.trekto.jos.core.impl.double_precision;

import com.aparapi.Range;
import info.trekto.jos.core.Simulation;
import info.trekto.jos.core.exceptions.SimulationException;
import info.trekto.jos.core.impl.ConditionVar;
import info.trekto.jos.core.impl.SimulationProperties;
import info.trekto.jos.core.impl.Statistics;
import info.trekto.jos.core.impl.StatsAverages;
import info.trekto.jos.core.impl.arbitrary_precision.SimulationAP;
import info.trekto.jos.core.impl.single_precision.CollisionCheckFloat;
import info.trekto.jos.core.impl.single_precision.SimulationLogicFloat;
import info.trekto.jos.core.impl.single_precision.StartThreadRoutineRunFloat;
import info.trekto.jos.core.model.SimulationObject;
import info.trekto.jos.core.model.impl.SimulationObjectImpl;
import info.trekto.jos.core.model.impl.TripleNumber;
import info.trekto.jos.core.numbers.New;
import info.trekto.jos.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Semaphore;

import static com.aparapi.Kernel.EXECUTION_MODE.GPU;
import static info.trekto.jos.core.Controller.C;
import static info.trekto.jos.util.Utils.*;
import static java.lang.System.exit;

/**
 * This implementation uses Aparapi library and runs on GPU if possible.
 *
 * @author Trayan Momkov
 * 2017-May-18
 */
public class SimulationDouble extends SimulationAP implements Simulation {
    private static final Logger logger = LoggerFactory.getLogger(SimulationDouble.class);

    private final SimulationLogicDouble simulationLogic;
    private final Range simulationLogicRange;
    private final CollisionCheckDouble collisionCheckKernel;
    private final Range collisionCheckRange;
    private final double[] zeroArray;
    private final SimulationAP cpuSimulation;
    private boolean executingOnCpu;

    private Thread[] threads;
    private StartThreadRoutineRunDouble[] runnables;

    public Statistics totalGlobalStats;    // accumulated stats of all iterations of all threads
    public Statistics partialGlobalStats;  // global stats of the last M iterations of all threads
    public StatsAverages statsAvgs;

    public Semaphore statsSem1 = new Semaphore(0);
    public Semaphore statsSem2 = new Semaphore(0);
    public final ConditionVar statsCount = new ConditionVar();

    public Thread thisThread;

    public SimulationDouble(SimulationProperties properties, SimulationAP cpuSimulation) {
        super(properties);
        final int n = properties.getNumberOfObjects();
        int screenWidth = 0;
        int screenHeight = 0;
        if (properties.isBounceFromScreenBorders()) {
            screenWidth = C.getVisualizer().getVisualizationPanel().getWidth();
            screenHeight = C.getVisualizer().getVisualizationPanel().getHeight();
        }
        simulationLogic = new SimulationLogicDouble(n, properties.getSecondsPerIteration().doubleValue(), screenWidth, screenHeight,
                properties.isMergeOnCollision(), properties.getCoefficientOfRestitution().doubleValue());
        zeroArray = new double[n];
        simulationLogicRange = Range.create(n);
//        simulationLogic.setExecutionMode(GPU);

        collisionCheckKernel = new CollisionCheckDouble(
                n,
                simulationLogic.positionX,
                simulationLogic.positionY,
                simulationLogic.radius,
                simulationLogic.deleted);
        collisionCheckRange = Range.create(n);
//        collisionCheckKernel.setExecutionMode(GPU);
        this.cpuSimulation = cpuSimulation;

        totalGlobalStats = new Statistics();
        partialGlobalStats = new Statistics();
        statsAvgs = new StatsAverages(properties.getNumberOfThreads());

        thisThread = Thread.currentThread();
        threads = new Thread[properties.getNumberOfThreads()];
        runnables = new StartThreadRoutineRunDouble[properties.getNumberOfThreads()];
    }

    public void doIteration(boolean saveCurrentIterationToFile, long iterationCounter) {
        deepCopy(simulationLogic.positionX, simulationLogic.readOnlyPositionX);
        deepCopy(simulationLogic.positionY, simulationLogic.readOnlyPositionY);
        deepCopy(simulationLogic.velocityX, simulationLogic.readOnlyVelocityX);
        deepCopy(simulationLogic.velocityY, simulationLogic.readOnlyVelocityY);
        deepCopy(simulationLogic.accelerationX, simulationLogic.readOnlyAccelerationX);
        deepCopy(simulationLogic.accelerationY, simulationLogic.readOnlyAccelerationY);
        deepCopy(simulationLogic.mass, simulationLogic.readOnlyMass);
        deepCopy(simulationLogic.radius, simulationLogic.readOnlyRadius);
        deepCopy(simulationLogic.color, simulationLogic.readOnlyColor);
        deepCopy(simulationLogic.deleted, simulationLogic.readOnlyDeleted);

        /* Execute in parallel on GPU if available */
        if (GPU.equals(simulationLogic.getExecutionMode())) {
            simulationLogic.execute(simulationLogicRange);
        } else if (this.properties.getNumberOfThreads() == 1) {
            simulationLogic.calculateAllNewValues();
        } else {
            try {
                if (areThreadsInterrumpted()) cancelThreads();
                simulationLogic.calculateAllNewValuesThreads(properties.getNumberOfThreads());
            } catch (InterruptedException e) {
                cancelThreads();
            }
        }
        if (iterationCounter == 1) {
            if (!GPU.equals(simulationLogic.getExecutionMode())) {
                warn(logger, "Simulation logic execution mode = " + simulationLogic.getExecutionMode());
            }
        }

        /* Collision */
        collisionCheckKernel.prepare();

        /* Execute in parallel on GPU if available */
        if (GPU.equals(simulationLogic.getExecutionMode()))
            collisionCheckKernel.execute(collisionCheckRange);
        else if (properties.getNumberOfThreads() == 1)
            collisionCheckKernel.checkAllCollisions();
        else {
            try {
                if (areThreadsInterrumpted()) cancelThreads();
                collisionCheckKernel.checkAllCollisionsThreads(properties.getNumberOfThreads());
            } catch (InterruptedException e) {
                cancelThreads();
            }
        }
        if (iterationCounter == 1) {
            if (!GPU.equals(collisionCheckKernel.getExecutionMode())) {
                warn(logger, "Collision detection execution mode = " + collisionCheckKernel.getExecutionMode());
            }
        }

        /* If collision/s exists execute sequentially on a single thread */
        if (collisionCheckKernel.collisionExists()) {
            simulationLogic.processCollisions();
            if (isConcurrentExecution())
                applyBalancing();
        }

        if (properties.isSaveToFile() && saveCurrentIterationToFile) {
            SimulationLogicDouble sl = simulationLogic;
            C.getReaderWriter().appendObjectsToFile(properties, iterationCounter, sl.positionX, sl.positionY, zeroArray, sl.velocityX, sl.velocityY,
                    zeroArray, sl.mass, sl.radius, sl.id, sl.color, sl.deleted, sl.accelerationX, sl.accelerationY,
                    zeroArray);
        }

        /* Stats */
        if (isConcurrentExecution() && doneMIters(iterationCounter)) {
            try {
                prepareMItersStats(iterationCounter);
            } catch (InterruptedException e) {
                cancelThreads();
            }
        }
    }

    private boolean areThreadsInterrumpted(){
        for (int i=0; i<threads.length; i++){
            if(runnables[i].thisThread!=null && runnables[i].thisThread.isInterrupted() || runnables[i].cancel)
                return true;
        }
        return false;
    }

    private void prepareMItersStats(long iterationCounter) throws InterruptedException{
        // Wait for all globalStats to be updated
        waitAllThreadsMain();
        statsAvgs.calculateAverages(partialGlobalStats.computTime, partialGlobalStats.partLoad);
        partialGlobalStats.reset();
        printGlobalStats(iterationCounter);

        // Notify threads to continue printing
        statsSem1.release(properties.getNumberOfThreads());

        // Wait all partialStats prints before applying balancing policy
        waitAllThreadsMain();
        System.out.println();
        statsSem2.release(properties.getNumberOfThreads());
    }

    private void cancelThreads() {
        for (int i = 0; i < threads.length; i++) {
            runnables[i].cancel();
        }
        joinThreads();
        exit(-1);
    }

    private void applyBalancing() {
        int numThreads = properties.getNumberOfThreads();
        int particles = simulationLogic.getCurrentParticles();

        System.out.println("part: " + particles);
        int remainingThreads = numThreads, work = particles, currentJob, firstNonAssigned = 0, first, last;

        // Create threads
        for (int i = 0; i < numThreads; i++) {
            currentJob = work / remainingThreads;
            first = firstNonAssigned;
            last = simulationLogic.getRangeforNPart(first, currentJob);

            runnables[i].first = first;
            runnables[i].last = simulationLogic.getRangeforNPart(first, currentJob);
            runnables[i].numPart = currentJob;

            firstNonAssigned = last;
            work -= currentJob;
            remainingThreads--;
        }
    }

    private void printGlobalStats(long iterationCounter) {
        String line = new String(new char[175]).replace("\0", "#");

        System.out.println(line + "\n" + line);
        System.out.println("[" + iterationCounter + "] Global Statistics " + String.format("%5d", simulationLogic.getCurrentParticles()) + "part" +
                "\t\t\t\tEvalPart: " + String.format("%,12d", totalGlobalStats.evalPart) +
                "\tFussPart: " + String.format("%,6d", totalGlobalStats.mergedPart) +
                "\tPartLoad: " + String.format("%,12d", totalGlobalStats.partLoad) + " (Desb: " + String.format("%.3f", 0.0) + "%)" +
                "\tCpuLoad: " + String.format("%12s", nanoToHumanReadable(totalGlobalStats.computTime)) + " (Desb: " + String.format("%.3f", 0.0) + "%)");
    }

    private void waitAllThreadsMain() {
        synchronized (statsCount) {
            while (statsCount.finishedThreads < properties.getNumberOfThreads()) {
                try {
                    statsCount.wait();
                } catch (InterruptedException e) {
                }
            }
            statsCount.finishedThreads = 0;
        }
    }

    public boolean doneMIters(long iter) {
        return iter % properties.getMforStats() == 0;
    }

    private boolean isConcurrentExecution() {
        return !GPU.equals(simulationLogic.getExecutionMode()) && properties.getNumberOfThreads() > 1;
    }

    public void initArrays(List<SimulationObject> initialObjects) {
        Arrays.fill(simulationLogic.deleted, true);
        for (int i = 0; i < initialObjects.size(); i++) {
            SimulationObject o = initialObjects.get(i);
            simulationLogic.positionX[i] = o.getX().doubleValue();
            simulationLogic.positionY[i] = o.getY().doubleValue();
            simulationLogic.velocityX[i] = o.getVelocity().getX().doubleValue();
            simulationLogic.velocityY[i] = o.getVelocity().getY().doubleValue();
            simulationLogic.accelerationX[i] = o.getAcceleration().getX().doubleValue();
            simulationLogic.accelerationY[i] = o.getAcceleration().getY().doubleValue();
            simulationLogic.mass[i] = o.getMass().doubleValue();
            simulationLogic.radius[i] = o.getRadius().doubleValue();
            simulationLogic.id[i] = o.getId();
            simulationLogic.color[i] = o.getColor();
            simulationLogic.deleted[i] = false;
        }
    }

    public void startThreadRoutine() {
        int numThreads = properties.getNumberOfThreads();
        long numIters = properties.isInfiniteSimulation() ? 0 : properties.getNumberOfIterations();
        int M = properties.getMforStats();
        totalGlobalStats = new Statistics();

        int particles = simulationLogic.positionX.length;
        threads = new Thread[numThreads];
        runnables = new StartThreadRoutineRunDouble[numThreads];

        int remainingThreads = numThreads, work = particles, currentJob, firstNonAssigned = 0;

        // Create threads
        for (int i = 0; i < numThreads; i++) {
            currentJob = work / remainingThreads;
            int first = firstNonAssigned, last = firstNonAssigned + currentJob;


            runnables[i] = new StartThreadRoutineRunDouble(i, first, last, currentJob, this);
            threads[i] = new Thread(runnables[i]);
            threads[i].start();

            work -= currentJob;
            remainingThreads--;
            firstNonAssigned += currentJob;
        }
    }

    private void joinThreads() {
        int numThreads = properties.getNumberOfThreads();
        for (int i = 0; i < numThreads; i++) {
            try {
                threads[i].join();
                System.out.println("Thread "+ runnables[i].id + " joined.");
            } catch (InterruptedException e) {
                for (int j = 0; j < numThreads; j++) runnables[j].cancel();
                exit(-1);
            }
        }
    }

    @Override
    public void startSimulation() throws SimulationException {
        init();

        info(logger, "Start simulation...");
        C.setEndText("END.");
        long startTime = System.nanoTime();
        long previousTime = startTime;
        long previousVisualizationTime = startTime;
        long endTime;

        C.setRunning(true);
        C.setHasToStop(false);
        try {
            //If running on CPU and more than 1 thread, create threads
            if (isConcurrentExecution())
                startThreadRoutine();

            for (long i = 0; properties.isInfiniteSimulation() || i < properties.getNumberOfIterations(); i++) {
                try {
                    if (C.hasToStop()) {
                        doStop();
                        break;
                    }
                    while (C.isPaused()) {
                        Thread.sleep(PAUSE_SLEEP_MILLISECONDS);
                    }

                    iterationCounter = i + 1;
                    int numberOfObjects = executingOnCpu ? cpuSimulation.getObjects().size() : countObjects();

                    if (cpuSimulation != null && !executingOnCpu && numberOfObjects <= C.getCpuThreshold()) {
                        cpuSimulation.initSwitchingFromGpu(convertToSimulationObjects());
                        executingOnCpu = true;
                    }

                    if (System.nanoTime() - previousTime >= NANOSECONDS_IN_ONE_SECOND * SHOW_REMAINING_INTERVAL_SECONDS) {
                        showRemainingTime(i, startTime, properties.getNumberOfIterations(), numberOfObjects);
                        previousTime = System.nanoTime();
                    }

                    boolean visualize = false;
                    if (properties.isRealTimeVisualization()) {
                        if (properties.getPlayingSpeed() < 0) {
                            /* Slow down */
                            Thread.sleep(-properties.getPlayingSpeed());
                            visualize = true;
                        } else if ((System.nanoTime() - previousVisualizationTime) / NANOSECONDS_IN_ONE_MILLISECOND >= properties.getPlayingSpeed()) {
                            visualize = true;
                        }
                    }

                    if (visualize) {
                        endTime = System.nanoTime();
                        if (executingOnCpu) {
                            C.getVisualizer().visualize(cpuSimulation.getObjects(), endTime - startTime, iterationCounter);
                        } else {
                            SimulationLogicDouble sl = simulationLogic;
                            C.getVisualizer().visualize(iterationCounter, numberOfObjects, endTime - startTime, sl.id, sl.deleted, sl.positionX, sl.positionY, sl.radius,
                                    sl.color);
                        }
                        previousVisualizationTime = System.nanoTime();
                    }

                    if (executingOnCpu) {
                        cpuSimulation.doIteration(i % properties.getSaveEveryNthIteration() == 0, iterationCounter);
                    } else {
                        doIteration(i % properties.getSaveEveryNthIteration() == 0, iterationCounter);
                    }
                } catch (InterruptedException e) {
                    error(logger, "Concurrency failure. One of the threads interrupted in cycle " + i, e);
                }
            }

            if (properties.isRealTimeVisualization()) {
                C.getVisualizer().end();
            }
            endTime = System.nanoTime();
        } finally {
            C.setRunning(false);
            if (properties.isSaveToFile()) {
                C.getReaderWriter().endFile();
            }
        }

        if (!GPU.equals(simulationLogic.getExecutionMode()) && properties.getNumberOfThreads() > 1)
            joinThreads();

        info(logger, "End of simulation. Time: " + nanoToHumanReadable(endTime - startTime));
    }

    private List<SimulationObject> convertToSimulationObjects() {
        List<SimulationObject> objects = new ArrayList<>();
        SimulationLogicDouble sl = simulationLogic;

        for (int i = 0; i < sl.positionX.length; i++) {
            if (!sl.deleted[i]) {
                SimulationObject simo = new SimulationObjectImpl();
                simo.setId(sl.id[i]);

                simo.setX(New.num(sl.positionX[i]));
                simo.setY(New.num(sl.positionY[i]));
                simo.setZ(New.num(0));

                simo.setMass(New.num(sl.mass[i]));

                simo.setVelocity(new TripleNumber(New.num(sl.velocityX[i]),
                        New.num(sl.velocityY[i]),
                        New.num(0)));

                simo.setAcceleration(new TripleNumber(New.num(sl.accelerationX[i]),
                        New.num(sl.accelerationY[i]),
                        New.num(0)));

                simo.setRadius(New.num(sl.radius[i]));
                simo.setColor(sl.color[i]);

                objects.add(simo);
            }
        }

        return objects;
    }

    public void init() throws SimulationException {
        initArrays(properties.getInitialObjects());
        if (duplicateIdExists(simulationLogic.id)) {
            throw new SimulationException("Objects with duplicate IDs exist!");
        }

        if (collisionExists(simulationLogic.positionX, simulationLogic.positionY, simulationLogic.radius)) {
            throw new SimulationException("Initial collision exists!");
        }

        executingOnCpu = false;

        info(logger, "Done.\n");
        Utils.printConfiguration(this);
    }

    private boolean duplicateIdExists(String[] id) {
        Set<String> ids = new HashSet<>();
        for (String objectId : id) {
            if (!ids.add(objectId)) {
                return true;
            }
        }
        return false;
    }

    public int countObjects() {
        int numberOfObjects = 0;
        for (int j = 0; j < simulationLogic.deleted.length; j++) {
            if (!simulationLogic.deleted[j]) {
                numberOfObjects++;
            }
        }
        return numberOfObjects;
    }

    public boolean collisionExists(double[] positionX, double[] positionY, double[] radius) {
        for (int i = 0; i < positionX.length; i++) {
            for (int j = 0; j < positionX.length; j++) {
                if (i == j) {
                    continue;
                }
                // distance between centres
                double distance = SimulationLogicDouble.calculateDistance(positionX[i], positionY[i], positionX[j], positionY[j]);

                if (distance < radius[i] + radius[j]) {
                    info(logger, String.format("Collision between object A(x:%f, y:%f, r:%f) and B(x:%f, y:%f, r:%f)",
                            positionX[i], positionY[i], radius[i], positionX[j], positionY[j], radius[j]));
                    return true;
                }
            }
        }
        return false;
    }

    public SimulationAP getCpuSimulation() {
        return cpuSimulation;
    }

    public SimulationLogicDouble getSimulationLogicDouble() {
        return simulationLogic;
    }

    public CollisionCheckDouble getCollisionCheckKernel() {
        return collisionCheckKernel;
    }
}
