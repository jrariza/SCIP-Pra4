package info.trekto.jos.core.impl;

public class Statistics {
    public double computTime;
    public long partLoad;
    public long evalPart;
    public long mergedPart;

    //average in the last M iterations (only for global stats)
    public double avgComputTime;
    public double avgPartLoad;

    public double timeImb;
    public double partLoadImb;


    public Statistics() {
        computTime = 0;
        partLoad = 0;
        evalPart = 0;
        mergedPart = 0;

        avgComputTime=0;
        avgPartLoad=0;
    }

    public void reset() {
        computTime = 0;
        partLoad = 0;
        evalPart = 0;
        mergedPart = 0;
    }


    public void updateStats(Statistics values) {
        computTime += values.computTime;
        partLoad += values.partLoad;
        evalPart += values.evalPart;
        mergedPart += values.mergedPart;
    }

    public synchronized void updateGlobalStats(Statistics values) {
        updateStats(values);
        avgComputTime += values.computTime;
        avgPartLoad += values.partLoad;
    }

     public void calculateAverages(int numThreads) {
         System.out.println(avgComputTime);
         System.out.println(avgPartLoad);
        avgComputTime /=  (double) numThreads;
        avgPartLoad /=  (double) numThreads;
         System.out.println(avgComputTime);
         System.out.println(avgPartLoad);
    }

    public void resetAverages() {
        avgComputTime = 0;
        avgPartLoad = 0;
    }
}

