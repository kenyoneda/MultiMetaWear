package com.example.android.bluetoothlegatt;

/**
 * Created by Ken on /13/0717.
 */

public class SensorRecord {
    private String timestamp;
    private float x;
    private float y;
    private float z;

    public SensorRecord(String timestamp, float x, float y, float z) {
        this.timestamp = timestamp;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getZ() {
        return z;
    }

    @Override
    public String toString() {
        return timestamp + ", " + x + ", " + y + ", " + z;
    }
}
