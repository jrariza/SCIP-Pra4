package info.trekto.jos.core.impl.single_precision;

public class CheckAllCollisionsRunFloat implements Runnable {
    private final CollisionCheckFloat collision;
    private final int first;
    private final int last;
    private volatile boolean cancel = false;
    private Thread thisThread = null;

    public CheckAllCollisionsRunFloat(CollisionCheckFloat collision, int first, int last) {
        this.collision = collision;
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
        for (int i = first; i < last && !cancel && !thisThread.isInterrupted(); i++) {
            collision.checkCollisions(i);
        }
    }
}
