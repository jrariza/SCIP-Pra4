package info.trekto.jos.core.impl.single_precision;

import com.aparapi.Kernel;
import info.trekto.jos.core.impl.ConditionVar;

import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class CollisionCheckFloat extends Kernel {
    public final boolean[] collisions;
    public final int n;

    public final float[] positionX;
    public final float[] positionY;
    public final float[] radius;
    public final boolean[] deleted;

    public Semaphore collisionsSem;
    public Lock collLock;
    public Condition finishedCollisions;
    public ConditionVar collCount;

    public CollisionCheckFloat(int n, float[] positionX, float[] positionY, float[] radius, boolean[] deleted) {
        this.n = n;
        collisions = new boolean[n];

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
        Arrays.fill(collisions, false);
    }

    public boolean collisionExists() {
        for (boolean collision : collisions) {
            if (collision) {
                return true;
            }
        }
        return false;
    }

    /**
     * !!! DO NOT CHANGE THIS METHOD and methods called from it if you don't have experience with Aparapi library!!!
     * This code is translated to OpenCL and executed on the GPU.
     * You cannot use even simple 'break' here - it is not supported by Aparapi.
     */
    @Override
    public void run() {
        int i = getGlobalId();
        if (!deleted[i]) {
            boolean collision = false;
            for (int j = 0; j < n; j++) {
                if (!collision && i != j && !deleted[j]) {
                    // distance between centres
                    float x = positionX[j] - positionX[i];
                    float y = positionY[j] - positionY[i];
                    float distance = (float) Math.sqrt(x * x + y * y);

                    if (distance < radius[i] + radius[j]) {
                        collision = true;
                        collisions[i] = true;
                    }
                }
            }
        }
    }

    public void checkAllCollisions() {
        for (int i = 0; i < positionX.length; i++) {
            if (!deleted[i]) {
                boolean collision = false;
                for (int j = 0; j < n; j++) {
                    if (!collision && i != j && !deleted[j]) {
                        // distance between centres
                        double x = positionX[j] - positionX[i];
                        double y = positionY[j] - positionY[i];
                        double distance = Math.sqrt(x * x + y * y);

                        if (distance < radius[i] + radius[j]) {
                            collision = true;
                            collisions[i] = true;
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

    public void checkCollisions(int i) {
        if (!deleted[i]) {
            boolean collision = false;
            for (int j = 0; j < n; j++) {
                if (!collision && i != j && !deleted[j]) {
                    // distance between centres
                    double x = positionX[j] - positionX[i];
                    double y = positionY[j] - positionY[i];
                    double distance = Math.sqrt(x * x + y * y);

                    if (distance < radius[i] + radius[j]) {
                        collision = true;
                        collisions[i] = true;
                    }
                }
            }
        }
    }
}
