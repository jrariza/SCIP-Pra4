package info.trekto.jos.core.impl;

public class ConditionVar {
    // Holds a conditional variable to count finished threads
    // Instances of this class will be used as shared locks between main and threads
    public int finishedThreads;

    public ConditionVar() {
        finishedThreads = 0;
    }
}
