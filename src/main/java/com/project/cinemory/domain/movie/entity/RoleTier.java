package com.project.cinemory.domain.movie.entity;

public enum RoleTier {
    LEAD(0.5), SUPPORTING(0.4), MINOR(0.1);

    private final double weight;

    RoleTier(double weight) {
        this.weight = weight;
    }

    public double getWeight() {
        return weight;
    }
}
