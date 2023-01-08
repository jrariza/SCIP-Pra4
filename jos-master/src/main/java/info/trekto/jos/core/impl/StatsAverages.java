package info.trekto.jos.core.impl;

public class StatsAverages {
    //averages in the last M iterations (only for global stats)
    public double avgComputTime;
    public double avgPartLoad;
    public int numThreads;

    public StatsAverages(int numThreads){
        avgComputTime=0;
        avgPartLoad=0;
        this.numThreads = numThreads;
    }

    public void calculateAverages(double computTime, long partLoad) {
        avgComputTime = computTime/ (double) numThreads;
        avgPartLoad = partLoad / (double) numThreads;
    }
}
