package com.project.cinemory.global.infra.publicdata.dto;

import java.util.List;

public record TheaterSourcePage(int totalCount, List<TheaterSourceRow> rows) {
}
