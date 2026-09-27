package com.project.cinemory.domain.collection.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import com.project.cinemory.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

@Entity
@Table(name = "collection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Collection extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    // 사용자 지정 순서(오름차순이 위). 음수·불연속 허용 — 상대값일 뿐이며 재작성 시 0..N-1로 정규화된다.
    @Column(name = "position", nullable = false)
    private int position;

    @Builder
    private Collection(User user, String name, String description, int position) {
        this.user = user;
        this.name = name;
        this.description = description;
        this.position = position;
    }

    public void update(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public void changePosition(int position) {
        this.position = position;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Collection that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
