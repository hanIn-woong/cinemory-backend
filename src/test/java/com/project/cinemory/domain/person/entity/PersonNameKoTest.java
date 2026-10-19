package com.project.cinemory.domain.person.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 인물 한글명 도메인 규칙(admin-spec 12-1, 테스트 12-5) — DB 없이 엔티티만 본다. */
class PersonNameKoTest {

    private Person person() {
        return Person.of(1L, "Tom Hanks", "/p.jpg");
    }

    @Test
    void 앞뒤_공백은_잘린다() {
        Person person = person();

        person.changeNameKo("  톰 행크스  ");

        assertThat(person.getNameKo()).isEqualTo("톰 행크스");
    }

    @Test
    void 빈_문자열과_null은_해제다() {
        Person person = person();
        person.changeNameKo("톰 행크스");

        person.changeNameKo("   ");
        assertThat(person.getNameKo()).isNull();

        person.changeNameKo("톰 행크스");
        person.changeNameKo(null);
        assertThat(person.getNameKo()).isNull();
    }

    @Test
    void 백자까지_허용하고_초과는_거부한다() {
        Person person = person();

        person.changeNameKo("가".repeat(100));
        assertThat(person.getNameKo()).hasSize(100);

        assertThatThrownBy(() -> person.changeNameKo("가".repeat(101))).isInstanceOf(IllegalArgumentException.class);
        // 거부되면 이전 값이 그대로다
        assertThat(person.getNameKo()).hasSize(100);
    }

    @Test
    void 공백을_자른_뒤_길이로_판정한다() {
        Person person = person();

        person.changeNameKo(" " + "가".repeat(100) + " ");

        assertThat(person.getNameKo()).hasSize(100);
    }

    @Test
    void 표시_이름은_한글명이_있으면_그것_없으면_원본이다() {
        Person person = person();
        assertThat(person.displayName()).isEqualTo("Tom Hanks");

        person.changeNameKo("톰 행크스");
        assertThat(person.displayName()).isEqualTo("톰 행크스");

        person.changeNameKo(null);
        assertThat(person.displayName()).isEqualTo("Tom Hanks");
    }

    @Test
    void 재동기화_경로_updateProfile은_한글명을_건드리지_않는다() {
        Person person = person();
        person.changeNameKo("톰 행크스");

        person.updateProfile("Thomas Jeffrey Hanks", "/new.jpg");

        assertThat(person.getName()).isEqualTo("Thomas Jeffrey Hanks");
        assertThat(person.getProfilePath()).isEqualTo("/new.jpg");
        assertThat(person.getNameKo()).isEqualTo("톰 행크스");
        assertThat(person.displayName()).isEqualTo("톰 행크스");
    }
}
