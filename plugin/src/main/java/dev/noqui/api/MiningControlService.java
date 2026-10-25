package dev.noqui.api;

/** Main-thread controls for the active mine, independent of the quiz session. */
public interface MiningControlService {
    record Status(int remainingOre,int initialOre,double resetThreshold,long remainingMillis,boolean ready) {}
    Status status();
    /** Regenerate the mine and start a fresh reset interval. Does not stop or restart the quiz. */
    void reset();
}
