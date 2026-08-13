# Conventional Commits 가이드

> 커밋 메시지 접두사 규칙 정리 (CineMory 프로젝트 참고용)

---

## 기본 형식

```
<타입>: <변경 내용 요약>

(선택) 본문 — 더 상세한 설명
```

예시:
```
feat: 영화 검색 화면 TMDB API 연동
fix: 위시리스트 중복 추가 버그 수정
```

---

## 타입(접두사) 목록

| 접두사 | 의미 | 사용 예시 |
|---|---|---|
| `feat` | 새로운 기능 추가 | `feat: 시청 기록 별점 입력 기능 추가` |
| `fix` | 버그 수정 | `fix: 캘린더 날짜 표시 오류 수정` |
| `docs` | 문서 수정 (코드 변경 없음) | `docs: README 설치 방법 업데이트` |
| `style` | 코드 포맷팅, 세미콜론 등 — 동작에 영향 없는 변경 | `style: 들여쓰기 정리` |
| `refactor` | 기능 변화 없이 코드 구조 개선 | `refactor: API 호출 함수 도메인별로 분리` |
| `test` | 테스트 코드 추가/수정 | `test: WatchRecord 엔티티 단위 테스트 추가` |
| `chore` | 빌드 설정, 패키지 매니저 등 기타 작업 | `chore: ESLint 설정 추가` |
| `perf` | 성능 개선 | `perf: FlatList 렌더링 최적화` |

---

## CineMory에서 자주 쓰게 될 패턴

### 프론트엔드 (cinemory-app)
```
feat: 프론트엔드 기본 폴더 구조 생성
feat: 홈 화면 UI 구현
feat: React Navigation 탭 구조 추가
fix: 위시리스트 중복 추가 버그 수정
refactor: 영화 카드 컴포넌트 분리
chore: Zustand, Axios 패키지 설치
```

### 백엔드 (cinemory-backend)
```
feat: User 엔티티 및 Repository 추가
feat: ERD v1.1 반영 — watch_record user_id 컬럼 추가
fix: TMDB API 응답 매핑 오류 수정
refactor: WatchRecord 엔티티 시청 1회 = 1 row 구조로 변경
test: CollectionMovie N:M 매핑 테스트 작성
docs: API 명세 주석 추가
```

---

## 참고 — Breaking Change 표시

기존 구조를 깨는 큰 변경(예: ERD 변경처럼 기존 데이터와 호환 안 되는 경우)은 타입 뒤에 `!`를 붙이거나 본문에 `BREAKING CHANGE:`를 명시하기도 함.

```
feat!: watch_record 테이블 구조 변경 (시청 1회 = 1 row)

BREAKING CHANGE: 기존 watch_date 단일 컬럼 방식에서
시청 1회당 row 1개 생성 방식으로 변경. 기존 데이터 마이그레이션 필요.
```

CineMory는 1인 프로젝트라 엄격하게 지킬 필요는 없지만, ERD 수정처럼 영향이 큰 변경에는 표시해두면 나중에 히스토리 추적이 쉬움.

---

## 참고 링크
- [Conventional Commits 공식 사이트](https://www.conventionalcommits.org/)
