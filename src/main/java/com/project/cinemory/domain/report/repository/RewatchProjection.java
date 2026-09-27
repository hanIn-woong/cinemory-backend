package com.project.cinemory.domain.report.repository;

public interface RewatchProjection {
    Long getMovieId();
    String getTitle();
    String getPosterPath();
    Long getWatchCount();
}
