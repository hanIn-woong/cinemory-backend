package com.project.cinemory.domain.report.repository;

import java.time.LocalDate;

public interface OldestWatchedProjection {
    Long getMovieId();
    String getTitle();
    LocalDate getReleaseDate();
}
