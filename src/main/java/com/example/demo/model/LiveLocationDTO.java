package com.example.demo.model;

public class LiveLocationDTO {
    private double latitude;
    private double longitude;

    public LiveLocationDTO() {}
    public LiveLocationDTO(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }
}
