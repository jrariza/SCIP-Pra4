package info.trekto.jos.core.impl;

public class Statistics {
    public long computTime;
    public long partLoad;
    public long evalPart;
    public long mergedPart;

    public double timeImb;
    public double partLoadImb;
    public double combinedImb;


    public Statistics() {
        computTime = 0;
        partLoad = 0;
        evalPart = 0;
        mergedPart = 0;

        timeImb = 0;
        partLoadImb = 0;
        combinedImb = 0;
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
        combinedImb = (timeImb + partLoadImb) / 2;
    }

    @Override
    public String toString() {
        return "Statistics{" +
                "computTime=" + computTime +
                ", partLoad=" + partLoad +
                ", evalPart=" + evalPart +
                ", mergedPart=" + mergedPart +
                ", timeImb=" + timeImb +
                ", partLoadImb=" + partLoadImb +
                '}';
    }
}

