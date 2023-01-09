package info.trekto.jos.core.impl.double_precision;

import com.aparapi.Kernel;
import info.trekto.jos.core.impl.ConditionVar;
import info.trekto.jos.core.impl.single_precision.CheckAllCollisionsRunFloat;

import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.System.exit;

public class CollisionCheckDouble extends Kernel {
    public final boolean[] collisionExists;
    public final int n;

    public final double[] positionX;
    public final double[] positionY;
    public final double[] radius;
    public final boolean[] deleted;

    public Semaphore collisionsSem;
    public Lock collLock;
    public Condition finishedCollisions;
    public ConditionVar collCount;

    public CollisionCheckDouble(int n, double[] positionX, double[] positionY, double[] radius, boolean[] deleted) {
        this.n = n;
        collisionExists = new boolean[1];

        this.positionX = positionX;
        this.positionY = positionY;
        this.radius = radius;
        this.deleted = deleted;

        collisionsSem = new Semaphore(0);
        collLock = new ReentrantLock();
        finishedCollisions = collLock.newCondition();
        collCount = new ConditionVar();
    }

    public void prepare() {
        collisionExists[0] = false;
    }

    public boolean collisionExists() {
        return collisionExists[0];
    }

    /**
     * !!! DO NOT CHANGE THIS METHOD and methods called from it if you don't have experience with Aparapi library!!!
     * This code is translated to OpenCL and executed on the GPU.
     * You cannot use even simple 'break' here - it is not supported by Aparapi.
     */
    @Override
    public void run() {
        if (collisionExists[0]) {
            return;
        }
        int i = getGlobalId();
        if (!deleted[i]) {
            for (int j = 0; j < n; j++) {
                if (i != j && !deleted[j]) {
                    // distance between centres
                    double x = positionX[j] - positionX[i];
                    double y = positionY[j] - positionY[i];
                    double distance = Math.sqrt(x * x + y * y);

                    if (distance < radius[i] + radius[j]) {
                        collisionExists[0] = true;
                        return;
                    }
                }
            }
        }
    }


    public void checkAllCollisions() {
        if (collisionExists[0]) {
            return;
        }
        for (int i = 0; i < positionX.length; i++) {
            if (!deleted[i]) {
                for (int j = 0; j < n; j++) {
                    if (i != j && !deleted[j]) {
                        // distance between centres
                        double x = positionX[j] - positionX[i];
                        double y = positionY[j] - positionY[i];
                        double distance = Math.sqrt(x * x + y * y);

                        if (distance < radius[i] + radius[j]) {
                            collisionExists[0] = true;
                            return;
                        }
                    }
                }
            }
        }
    }

    public void checkAllCollisionsThreads(int MThreads) throws InterruptedException {
        // Notify threads to start checkCollisions
        collisionsSem.release(MThreads);

        // Wait for all threads to finish
        collLock.lock();
        try {
            if (collCount.finishedThreads < MThreads)
                finishedCollisions.await();
            collCount.finishedThreads = 0;
        } finally {
            collLock.unlock();
        }
    }

    public long checkCollisions(int i) {
        if (!deleted[i]) {
            for (int j = 0; j < n; j++) {
                if (i != j && !deleted[j]) {
                    // distance between centres
                    double x = positionX[j] - positionX[i];
                    double y = positionY[j] - positionY[i];
                    double distance = Math.sqrt(x * x + y * y);

                    if (distance < radius[i] + radius[j]) {
                        collisionExists[0] = true;
                        return 1;
                    }
                }
            }
        }
        return 0;
    }

}
