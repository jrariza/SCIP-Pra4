package info.trekto.jos.core.impl.double_precision;


public class CheckAllCollisionsRunDouble implements Runnable {
    private final CollisionCheckDouble collision;
    private final int first;
    private final int last;
    private volatile boolean cancel = false;
    private Thread thisThread = null;

    public CheckAllCollisionsRunDouble(CollisionCheckDouble collision, int first, int last) {
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
