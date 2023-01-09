package info.trekto.jos.core.impl;

public class Statistics {
    public long computTime;
    public long partLoad;
    public long evalPart;
    public long mergedPart;

    public double timeImb;
    public double partLoadImb;


    public Statistics() {
        computTime = 0;
        partLoad = 0;
        evalPart = 0;
        mergedPart = 0;

        timeImb = 0;
        partLoadImb = 0;
    }

    public void reset() {
        computTime = 0;
        partLoad = 0;
        evalPart = 0;
        mergedPart = 0;
    }


    public synchronized void updateStats(Statistics values) {
        computTime += values.computTime;
        partLoad += values.partLoad;
        evalPart += values.evalPart;
        mergedPart += values.mergedPart;

        timeImb += values.timeImb;
        partLoadImb += values.partLoadImb;
    }

    public void calculateImbalances(double avgT, double avgP) {
        timeImb = (computTime - avgT) / avgT;
        partLoadImb = ((double) partLoad - avgP) / avgP;
    }
}

