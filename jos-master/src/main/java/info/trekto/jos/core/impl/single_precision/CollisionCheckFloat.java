package info.trekto.jos.core.impl.single_precision;

import com.aparapi.Kernel;

import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.System.exit;

public class CollisionCheckFloat extends Kernel {
    public final boolean[] collisions;
    public final int n;

    public final float[] positionX;
    public final float[] positionY;
    public final float[] radius;
    public final boolean[] deleted;

    public Semaphore collisionsSem;
    public int finishedThreads;
    public Lock lock;
    public Condition finishedCollisions;

    public CollisionCheckFloat(int n, float[] positionX, float[] positionY, float[] radius, boolean[] deleted) {
        this.n = n;
        collisions = new boolean[n];

        this.positionX = positionX;
        this.positionY = positionY;
        this.radius = radius;
        this.deleted = deleted;

        collisionsSem = new Semaphore(0);
        finishedThreads = 0;
        lock = new ReentrantLock();
        finishedCollisions = lock.newCondition();
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

    public void checkAllCollisionsThreads(int MThreads) {
        System.out.println("main2");
        // Notify threads to start checkCollisions
        collisionsSem.release(MThreads);

        // Wait for all threads to finish
        lock.lock();
        System.out.println("main in lock");
        try {
            if (finishedThreads < MThreads)
                finishedCollisions.await();
        } catch (InterruptedException e) {
        } finally {
            lock.unlock();
        }
        finishedThreads = 0;
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
