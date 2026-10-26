package com.project.cinemory.global.infra.gemini;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorCodecTest {

    private static float[] randomVector(long seed) {
        Random random = new Random(seed);
        float[] v = new float[VectorCodec.DIMENSIONS];
        for (int i = 0; i < v.length; i++) {
            v[i] = random.nextFloat() * 2 - 1;
        }
        return v;
    }

    @Test
    void 왕복_변환하면_원본과_비트까지_같다() {
        float[] original = randomVector(1);
        original[0] = Float.MIN_VALUE;
        original[1] = -0.0f;

        float[] decoded = VectorCodec.decode(VectorCodec.encode(original));

        assertThat(decoded).containsExactly(original);
    }

    @Test
    void 바이트_길이는_3072이고_little_endian이다() {
        float[] vector = new float[VectorCodec.DIMENSIONS];
        vector[0] = 1.0f;

        byte[] bytes = VectorCodec.encode(vector);

        assertThat(bytes).hasSize(3072);
        // 1.0f = 0x3F800000 — little-endian이면 낮은 바이트부터 00 00 80 3F.
        assertThat(bytes[0]).isEqualTo((byte) 0x00);
        assertThat(bytes[1]).isEqualTo((byte) 0x00);
        assertThat(bytes[2]).isEqualTo((byte) 0x80);
        assertThat(bytes[3]).isEqualTo((byte) 0x3F);
        assertThat(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat(0)).isEqualTo(1.0f);
    }

    @Test
    void decodeInto는_지정한_오프셋에_채운다() {
        float[] a = randomVector(2);
        float[] b = randomVector(3);
        float[] target = new float[VectorCodec.DIMENSIONS * 2];

        VectorCodec.decodeInto(VectorCodec.encode(a), target, 0);
        VectorCodec.decodeInto(VectorCodec.encode(b), target, VectorCodec.DIMENSIONS);

        float[] second = new float[VectorCodec.DIMENSIONS];
        System.arraycopy(target, VectorCodec.DIMENSIONS, second, 0, VectorCodec.DIMENSIONS);
        assertThat(second).containsExactly(b);
        assertThat(target[0]).isEqualTo(a[0]);
    }

    @Test
    void 차원이_다르면_거부한다() {
        assertThatThrownBy(() -> VectorCodec.encode(new float[10])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VectorCodec.decode(new byte[100])).isInstanceOf(IllegalArgumentException.class);
    }
}
