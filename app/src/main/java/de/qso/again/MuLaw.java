package de.qso.again;

/**
 * G.711 µ-law codec: 16-bit signed PCM <-> 8-bit µ-law.
 * Effective dynamic range ~14 bits via logarithmic companding,
 * for ~50% storage of linear PCM at telephone-grade quality.
 */
final class MuLaw {
    private static final int BIAS = 0x84;
    private static final int CLIP = 32635;
    private static final short[] DECODE_TABLE = new short[256];

    static {
        for (int i = 0; i < 256; i++) {
            int t = ~i & 0xFF;
            int sign = t & 0x80;
            int exponent = (t >> 4) & 0x07;
            int mantissa = t & 0x0F;
            int sample = ((mantissa << 3) + BIAS) << exponent;
            sample -= BIAS;
            DECODE_TABLE[i] = (short) (sign != 0 ? -sample : sample);
        }
    }

    private MuLaw() {}

    public static byte encode(int pcm16) {
        int sign = (pcm16 >> 8) & 0x80;
        if (sign != 0) pcm16 = -pcm16;
        if (pcm16 > CLIP) pcm16 = CLIP;
        pcm16 += BIAS;
        int exponent = 7;
        for (int expMask = 0x4000; (pcm16 & expMask) == 0 && exponent > 0; expMask >>= 1) {
            exponent--;
        }
        int mantissa = (pcm16 >> (exponent + 3)) & 0x0F;
        return (byte) (~(sign | (exponent << 4) | mantissa));
    }

    /** Encode 16-bit little-endian PCM into µ-law. Returns number of µ-law bytes written. */
    public static int encodePcm16(byte[] pcm16, int pcmOffset, int pcmLen, byte[] out, int outOffset) {
        int n = pcmLen / 2;
        for (int i = 0; i < n; i++) {
            int lo = pcm16[pcmOffset + i * 2] & 0xFF;
            int hi = pcm16[pcmOffset + i * 2 + 1];
            out[outOffset + i] = encode((hi << 8) | lo);
        }
        return n;
    }

    /** Decode µ-law into 16-bit little-endian PCM. Returns number of PCM bytes written. */
    public static int decodeToPcm16(byte[] mulaw, int srcOffset, int srcLen, byte[] out, int outOffset) {
        for (int i = 0; i < srcLen; i++) {
            short s = DECODE_TABLE[mulaw[srcOffset + i] & 0xFF];
            out[outOffset + i * 2] = (byte) (s & 0xFF);
            out[outOffset + i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return srcLen * 2;
    }
}
