package com.project.cinemory.global.infra.gemini;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@code float[]} ↔ {@code byte[]} 변환의 <b>유일한 곳</b> (M3b-recommendation-spec E-4-1).
 *
 * <p>바이트 순서는 little-endian 고정. 쓰기·읽기가 어긋나면 예외 없이 엉뚱한 유사도가 나오는 유형이라
 * 변환을 이 클래스 밖에 두지 않는다.
 */
public final class VectorCodec {

    /** {@code movie_embedding.embedding}은 {@code VARBINARY(3072)} = 768 x 4B. 차원 변경은 마이그레이션이 필요하다. */
    public static final int DIMENSIONS = 768;
    public static final int BYTES = DIMENSIONS * Float.BYTES;

    private VectorCodec() {
    }

    public static byte[] encode(float[] vector) {
        if (vector.length != DIMENSIONS) {
            throw new IllegalArgumentException("벡터 차원이 " + DIMENSIONS + "이 아닙니다: " + vector.length);
        }
        ByteBuffer buffer = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(vector);
        return buffer.array();
    }

    public static float[] decode(byte[] bytes) {
        float[] vector = new float[DIMENSIONS];
        decodeInto(bytes, vector, 0);
        return vector;
    }

    /** 인덱스 적재용 — 임시 배열 없이 연속 배열의 {@code offset}부터 채운다. */
    public static void decodeInto(byte[] bytes, float[] target, int offset) {
        if (bytes.length != BYTES) {
            throw new IllegalArgumentException("벡터 바이트 길이가 " + BYTES + "이 아닙니다: " + bytes.length);
        }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(target, offset, DIMENSIONS);
    }
}
