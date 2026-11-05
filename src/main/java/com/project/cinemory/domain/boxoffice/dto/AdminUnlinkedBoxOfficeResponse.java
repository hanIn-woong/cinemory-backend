package com.project.cinemory.domain.boxoffice.dto;

import com.project.cinemory.domain.boxoffice.repository.UnlinkedBoxOfficeGroup;

import java.time.LocalDate;

public record AdminUnlinkedBoxOfficeResponse(
        String koficMovieCd, String title, LocalDate openDate,
        LocalDate firstDate, LocalDate lastDate, Integer bestRank, long recordCount) {

    public static AdminUnlinkedBoxOfficeResponse from(UnlinkedBoxOfficeGroup g) {
        return new AdminUnlinkedBoxOfficeResponse(g.koficMovieCd(), g.title(), g.openDate(),
                g.firstDate(), g.lastDate(), g.bestRank(), g.recordCount());
    }
}
