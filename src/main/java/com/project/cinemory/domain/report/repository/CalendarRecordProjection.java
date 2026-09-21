package com.project.cinemory.domain.report.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface CalendarRecordProjection {
    Long getRecordId();
    Long getMovieId();
    String getTitle();
    String getPosterPath();
    BigDecimal getRating();
    LocalDate getWatchDate();
}
