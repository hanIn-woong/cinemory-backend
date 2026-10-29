-- 소셜 피드(social-feed-spec F-1) — 활동 6종 합성 조회용 인덱스. 설계 근거·EXPLAIN·롤백은 docs/schema/v35-delta.sql.
-- 확장(인덱스 추가)만 한다. 이 파일은 동결 — 절대 수정 금지.
CREATE INDEX idx_watch_record_user_created ON watch_record (user_id, created_at);
CREATE INDEX idx_review_user_created ON review (user_id, created_at);
CREATE INDEX idx_wish_movie_user_created ON wish_movie (user_id, created_at);
CREATE INDEX idx_collection_user_created ON collection (user_id, created_at);
CREATE INDEX idx_follow_follower_created ON follow (follower_id, created_at);
CREATE INDEX idx_collection_movie_coll_created ON collection_movie (collection_id, created_at);
