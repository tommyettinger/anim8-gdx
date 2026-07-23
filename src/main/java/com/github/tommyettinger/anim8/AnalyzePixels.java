package com.github.tommyettinger.anim8;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.FloatArray;

import java.util.Arrays;

import static com.github.tommyettinger.anim8.PaletteReducer.fromLinearLUT;
import static com.github.tommyettinger.anim8.PaletteReducer.toLinearLUT;
import static com.github.tommyettinger.anim8.PaletteReducer.TRI_BLUE_NOISE;
import static com.github.tommyettinger.anim8.PaletteReducer.TRI_BLUE_NOISE_B;
import static com.github.tommyettinger.anim8.PaletteReducer.TRI_BLUE_NOISE_C;
import static com.github.tommyettinger.anim8.PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS;
import static com.github.tommyettinger.anim8.PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS_B;
import static com.github.tommyettinger.anim8.PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS_C;
import static com.github.tommyettinger.anim8.PaletteReducer.shrink;
import static com.github.tommyettinger.anim8.PaletteReducer.TBM_MASK;
import static com.github.tommyettinger.anim8.PaletteReducer.TBM_BITS;
import static com.github.tommyettinger.anim8.PaletteReducer.TRI_BAYER_MATRIX_128;
import static com.github.tommyettinger.anim8.PaletteReducer.thresholdMatrix64;

public class AnalyzePixels {

    Integer idx;
    Pixmap image;
    Dithered.DitherAlgorithm ditherAlgorithm;
    int width;
    int height;
    boolean flipY;
    PaletteReducer palette;
    float ditherStrength;
    boolean fastAnalysis;
    boolean[] usedEntry = new boolean[256];
    int seq;
    byte[] indexedPixels;
    byte[] colorTab;

    public AnalyzePixels(Integer idx, Pixmap image, PaletteReducer palette, Dithered.DitherAlgorithm ditherAlgorithm, float ditherStrength, boolean fastAnalysis, boolean flipY) {
        this.idx = idx;
        this.image = image;
        this.seq = idx + 1;
        this.width = image.getWidth();
        this.height = image.getHeight();
        this.ditherAlgorithm = ditherAlgorithm;
        this.ditherStrength = ditherStrength;
        this.fastAnalysis = fastAnalysis;
        this.flipY = flipY;
        
        // We need a local PaletteReducer to have local error buffers for thread safety
        this.palette = new PaletteReducer();
        if (palette != null) {
            this.palette.exact(palette.paletteArray, palette.paletteMapping);
        } else {
            if (idx > 0) {
                if (fastAnalysis)
                    this.palette.analyzeFast(image, 300, 256);
                else
                    this.palette.analyze(image, 300, 256);
            } else {
                this.palette.analyze(image, 100, 256);
            }
        }
    }

    protected AnalyzedPixmap analyzePixels() {
        int nPix = width * height;
        indexedPixels = new byte[nPix];
        palette.setDitherStrength(ditherStrength);
        
        final int[] paletteArray = palette.paletteArray;

        colorTab = new byte[256 * 3];
        for (int i = 0, bi = 0; i < 256; i++) {
            int pa = paletteArray[i];
            colorTab[bi++] = (byte) (pa >>> 24);
            colorTab[bi++] = (byte) (pa >>> 16);
            colorTab[bi++] = (byte) (pa >>> 8);
            usedEntry[i] = false;
        }

        switch (ditherAlgorithm) {
            case NONE: analyzeNone(); break;
            case PATTERN: analyzePattern(); break;
            case CHAOTIC_NOISE: analyzeChaotic(); break;
            case GRADIENT_NOISE: analyzeGradient(); break;
            case ADDITIVE: analyzeAdditive(); break;
            case ROBERTS: analyzeRoberts(); break;
            case LOAF: analyzeLoaf(); break;
            case DIFFUSION: analyzeDiffusion(); break;
            case BLUE_NOISE: analyzeBlue(); break;
            case BAYER: analyzeBayer(); break;
            case BAYDIENT: analyzeBaydient(); break;
            case BLUNT: analyzeBlunt(); break;
            case BANTER: analyzeBanter(); break;
            case SCATTER: analyzeScatter(); break;
            case WOVEN: analyzeWoven(); break;
            case DODGY: analyzeDodgy(); break;
            case NEUE: analyzeNeue(); break;
            case OVERBOARD: analyzeOverboard(); break;
            case BURKES: analyzeBurkes(); break;
            case OCEANIC: analyzeOceanic(); break;
            case SEASIDE: analyzeSeaside(); break;
            case GOURD: analyzeGourd(); break;
            case MARTEN: analyzeMarten(); break;
            case WREN:
            default: analyzeWren(); break;
        }

        return new AnalyzedPixmap(idx, colorTab, indexedPixels);
    }

    protected void analyzeNone() {
        final int nPix = indexedPixels.length;
        int color;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = palette.paletteArray[0] == 0;

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    usedEntry[(indexedPixels[i] = paletteMapping[
                            (color >>> 17 & 0x7C00)
                                    | (color >>> 14 & 0x3E0)
                                    | ((color >>> 11 & 0x1F))]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzePattern() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        int cr, cg, cb, usedIndex;
        final float errorMul = ditherStrength * 0.5f / palette.populationBias;
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    int er = 0, eg = 0, eb = 0;
                    cr = (color >>> 24);
                    cg = (color >>> 16 & 0xFF);
                    cb = (color >>> 8 & 0xFF);
                    for (int c = 0; c < 16; c++) {
                        int rr = Math.min(Math.max((int) (cr + er * errorMul), 0), 255);
                        int gg = Math.min(Math.max((int) (cg + eg * errorMul), 0), 255);
                        int bb = Math.min(Math.max((int) (cb + eb * errorMul), 0), 255);
                        usedIndex = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))] & 0xFF;
                        palette.candidates[c | 16] = shrink(used = paletteArray[palette.candidates[c] = usedIndex]);
                        er += cr - (used >>> 24);
                        eg += cg - (used >>> 16 & 0xFF);
                        eb += cb - (used >>> 8 & 0xFF);
                    }
                    PaletteReducer.sort16(palette.candidates);
                    usedEntry[(indexedPixels[i] = (byte) palette.candidates[
                            PaletteReducer.thresholdMatrix16[((px & 3) | (y & 3) << 2)]]
                    ) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeChaotic() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        double adj, strength = ditherStrength * palette.populationBias * 1.5;
        long s = 0xC13FA9A902A6328FL * seq;
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    int rr = ((color >>> 24)       );
                    int gg = ((color >>> 16) & 0xFF);
                    int bb = ((color >>> 8)  & 0xFF);
                    used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF];
                    adj = ((TRI_BLUE_NOISE[(px & 63) | (y & 63) << 6] + 0.5f) * 0.007843138f);
                    adj *= adj * adj;
                    adj += ((px + y & 1) - 0.5f) * 0x1.8p-49 * strength *
                            (((s ^ 0x9E3779B97F4A7C15L) * 0xC6BC279692B5CC83L >> 15) +
                                    ((~s ^ 0xDB4F0B9175AE2165L) * 0xD1B54A32D192ED03L >> 15) +
                                    ((s = (s ^ rr + gg + bb) * 0xD1342543DE82EF95L + 0x91E10DA5C79E7B1DL) >> 15));
                    rr = Math.min(Math.max((int) (rr + (adj * ((rr - (used >>> 24))))), 0), 0xFF);
                    gg = Math.min(Math.max((int) (gg + (adj * ((gg - (used >>> 16 & 0xFF))))), 0), 0xFF);
                    bb = Math.min(Math.max((int) (bb + (adj * ((bb - (used >>> 8 & 0xFF))))), 0), 0xFF);
                    usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeGradient() {
        final int nPix = indexedPixels.length;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = palette.paletteArray[0] == 0;

        final float strength = 0.25f * ditherStrength * (palette.colorCount <= 128
                ? MathUtils.map(6, 180f, 3.15f, 1f, palette.colorCount)
                : MathUtils.map(128f, 256f, 1.6425288f, 1f, palette.colorCount));
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                int color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + ((142 * (px + 0x5F) + 79 * (y - 0x96) & 255) - 127.5f) * strength, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16 & 0xFF)] + ((142 * (px + 0xFA) + 79 * (y - 0xA3) & 255) - 127.5f) * strength, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8 & 0xFF) ] + ((142 * (px + 0xA5) + 79 * (y - 0xC9) & 255) - 127.5f) * strength, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeAdditive() {
        final int nPix = indexedPixels.length;
        int color;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = palette.paletteArray[0] == 0;

        final float strength = 0.25f * ditherStrength * (palette.colorCount <= 128
                ? MathUtils.map(6, 180f, 3.15f, 1f, palette.colorCount)
                : MathUtils.map(128f, 256f, 1.6425288f, 1f, palette.colorCount));
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + ((119 * px + 180 * y + 54 & 255) - 127.5f) * strength, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16 & 0xFF)] + ((119 * px + 180 * y + 81 & 255) - 127.5f) * strength, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8 & 0xFF) ] + ((119 * px + 180 * y      & 255) - 127.5f) * strength, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeRoberts() {
        final int nPix = indexedPixels.length;
        int color;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = palette.paletteArray[0] == 0;

        final float str = 45f * ditherStrength * (palette.colorCount <= 128
                ? MathUtils.map(6, 180f, 3.15f, 1f, palette.colorCount)
                : MathUtils.map(128f, 256f, 1.6425288f, 1f, palette.colorCount));
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    final float theta = ((px * 0xC13FA9A9 + y * 0x91E10DA5 >>> 9) * 0x1p-23f);
                    int rr = fromLinearLUT[(int) Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + OtherMath.triangleWave(theta         ) * str, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int) Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + OtherMath.triangleWave(theta + 0.209f) * str, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int) Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + OtherMath.triangleWave(theta + 0.518f) * str, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeLoaf() {
        final int nPix = indexedPixels.length;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = palette.paletteArray[0] == 0;

        final float strength = 5f * ditherStrength * (float)Math.pow(palette.colorCount, -0.4f);
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                int color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    int adj = (int)((((px + y & 1) << 5) - 16) * strength);
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + adj, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + adj, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + adj, 0), 1023)] & 255;
                    int rgb555 = ((rr << 7) & 0x7C00) | ((gg << 2) & 0x3E0) | ((bb >>> 3));
                    usedEntry[(indexedPixels[i] = paletteMapping[rgb555]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeGourd() {
        final int nPix = indexedPixels.length;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = palette.paletteArray[0] == 0;

        final float strength = ditherStrength * (palette.colorCount <= 128
                ? MathUtils.map(6, 180f, 3.15f, 1f, palette.colorCount)
                : MathUtils.map(128f, 256f, 1.6425288f, 1f, palette.colorCount));
        float[] tempMatrix = new float[64];
        for (int i = 0; i < 64; i++) {
            tempMatrix[i] = Math.min(Math.max((PaletteReducer.thresholdMatrix64[i] - 31.5f) * strength, -127), 127);
        }
        for (int oy = 0, i = 0; oy < height && i < nPix; oy++) {
            int y = flipped + flipDir * oy;
            for (int x = 0; x < width & i < nPix; x++) {
                int color = image.getPixel(x, y);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    float adj = tempMatrix[(x & 7) | (oy & 7) << 3];
                    int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + adj)] & 255;
                    int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + adj)] & 255;
                    int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + adj)] & 255;
                    int rgb555 = ((rr << 7) & 0x7C00) | ((gg << 2) & 0x3E0) | ((bb >>> 3));
                    usedEntry[(indexedPixels[i] = paletteMapping[rgb555]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeDiffusion() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        float rdiff, gdiff, bdiff;
        byte paletteIndex;
        float w1 = ditherStrength * 32 / palette.populationBias, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

        float[] curErrorRed = new float[w], nextErrorRed = new float[w],
                curErrorGreen = new float[w], nextErrorGreen = new float[w],
                curErrorBlue = new float[w], nextErrorBlue = new float[w];

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0f);
            Arrays.fill(nextErrorGreen, 0f);
            Arrays.fill(nextErrorBlue, 0f);

            int py = flipped + flipDir * y, ny = y + 1;

            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + curErrorRed[px]  , 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + curErrorGreen[px], 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + curErrorBlue[px] , 0), 1023)] & 255;

                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                    used = paletteArray[paletteIndex & 0xFF];
                    rdiff = Math.min(Math.max(0x1p-8f * ((color>>>24)-    (used>>>24))    , -1), 1);
                    gdiff = Math.min(Math.max(0x1p-8f * ((color>>>16&255)-(used>>>16&255)), -1), 1);
                    bdiff = Math.min(Math.max(0x1p-8f * ((color>>>8&255)- (used>>>8&255)) , -1), 1);

                    if(px < w - 1) {
                        curErrorRed[px+1]   += rdiff * w7;
                        curErrorGreen[px+1] += gdiff * w7;
                        curErrorBlue[px+1]  += bdiff * w7;
                    }
                    if(ny < height) {
                        if(px > 0) {
                            nextErrorRed[px-1]   += rdiff * w3;
                            nextErrorGreen[px-1] += gdiff * w3;
                            nextErrorBlue[px-1]  += bdiff * w3;
                        }
                        if(px < w - 1) {
                            nextErrorRed[px+1]   += rdiff * w1;
                            nextErrorGreen[px+1] += gdiff * w1;
                            nextErrorBlue[px+1]  += bdiff * w1;
                        }
                        nextErrorRed[px]   += rdiff * w5;
                        nextErrorGreen[px] += gdiff * w5;
                        nextErrorBlue[px]  += bdiff * w5;
                    }
                    i++;
                }
            }
        }
    }

    protected void analyzeBlue() {
        final int nPix = indexedPixels.length;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = palette.paletteArray[0] == 0;

        final float strength = 1.25f * ditherStrength * (float)Math.pow(palette.colorCount, -0.4f);
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            int ny = flipped + flipDir * y;
            for (int x = 0; x < width & i < nPix; x++) {
                int color = image.getPixel(x, ny);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    float adj = Math.min(Math.max(((TRI_BLUE_NOISE  [(x & 63) | (ny & 63) << 6] + ((x + ny & 1) << 8) - 127.5f) * strength), -100.5f), 101.5f);
                    int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + adj)] & 255;
                    int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + adj)] & 255;
                    int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + adj)] & 255;

                    usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeBayer() {
        final int nPix = indexedPixels.length;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = palette.paletteArray[0] == 0;

        final float strength = 10f * ditherStrength * (float)Math.pow(palette.colorCount, -0.4f);
        for (int oy = 0, i = 0; oy < height && i < nPix; oy++) {
            int y = flipped + flipDir * oy;
            for (int x = 0; x < width & i < nPix; x++) {
                int color = image.getPixel(x, y);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    float adj = (thresholdMatrix64[((x & 7) | (oy & 7) << 3)] - 31.5f) * strength;
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + adj, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + adj, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + adj, 0), 1023)] & 255;

                    usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeBaydient() {
        final int nPix = indexedPixels.length;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = palette.paletteArray[0] == 0;

        final float ignStrength = 2f * ditherStrength * (float)Math.pow(palette.colorCount, -0.4f);
        final float bayerStrength = ignStrength * 0.15f;
        for (int oy = 0, i = 0; oy < height && i < nPix; oy++) {
            int y = flipped + flipDir * oy;
            for (int x = 0; x < width & i < nPix; x++) {
                int color = image.getPixel(x, y);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    float ord = (thresholdMatrix64[((x & 7) | (oy & 7) << 3)] - 31.5f) * bayerStrength;
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + ord + ((142 * (x + 0x5F) + 79 * (y - 0x96) & 255) - 127.5f) * ignStrength, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16 & 0xFF)] + ord + ((142 * (x + 0xFA) + 79 * (y - 0xA3) & 255) - 127.5f) * ignStrength, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8 & 0xFF) ] + ord + ((142 * (x + 0xA5) + 79 * (y - 0xC9) & 255) - 127.5f) * ignStrength, 0), 1023)] & 255;

                    usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeBlunt() {
        final int nPix = indexedPixels.length;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = palette.paletteArray[0] == 0;

        final float strength = 1.5f * ditherStrength * (float)Math.pow(palette.colorCount, -0.4f);
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            int ny = flipped + flipDir * y;
            for (int x = 0; x < width & i < nPix; x++) {
                int color = image.getPixel(x, ny);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    float adj = (x+y<<7&128)-63.5f;
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + (TRI_BLUE_NOISE  [(x + 62 & 63) << 6 | (y + 66  & 63)] + adj) * strength, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + (TRI_BLUE_NOISE_B[(x + 31 & 63) << 6 | (y + 113 & 63)] + adj) * strength, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + (TRI_BLUE_NOISE_C[(x + 71 & 63) << 6 | (y + 41  & 63)] + adj) * strength, 0), 1023)] & 255;

                    usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeBanter() {
        final int nPix = indexedPixels.length;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = palette.paletteArray[0] == 0;

        final float strength = 3.5f * ditherStrength * (float)Math.pow(palette.colorCount, -0.4f);
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            int ny = flipped + flipDir * y;
            for (int x = 0; x < width & i < nPix; x++) {
                int color = image.getPixel(x, ny);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    float adj = (TRI_BAYER_MATRIX_128[(x & TBM_MASK) << TBM_BITS | (ny & TBM_MASK)] + 0.5f) * strength;
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + adj, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + adj, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + adj, 0), 1023)] & 255;

                    usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeScatter() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        final float w1 = Math.min(ditherStrength * 5.5f / (palette.populationBias * palette.populationBias), 16f),
                w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

        float[] curErrorRed = new float[w], nextErrorRed = new float[w],
                curErrorGreen = new float[w], nextErrorGreen = new float[w],
                curErrorBlue = new float[w], nextErrorBlue = new float[w];

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0f);
            Arrays.fill(nextErrorGreen, 0f);
            Arrays.fill(nextErrorBlue, 0f);

            int py = flipped + flipDir * y, ny = y + 1;

            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    float tbn = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS[(px & 63) | ((py << 6) & 0xFC0)];
                    er = curErrorRed[px] * tbn;
                    eg = curErrorGreen[px] * tbn;
                    eb = curErrorBlue[px] * tbn;
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                    used = paletteArray[paletteIndex & 0xFF];
                    rdiff = (0x2.1p-8f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x2.1p-8f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x2.1p-8f * ((color>>>8&255)- (used>>>8&255)) );
                    rdiff /= (0.125f + Math.abs(rdiff));
                    gdiff /= (0.5f + Math.abs(gdiff));
                    bdiff /= (0.5f + Math.abs(bdiff));
                    if(px < w - 1) {
                        curErrorRed[px+1]   += rdiff * w7;
                        curErrorGreen[px+1] += gdiff * w7;
                        curErrorBlue[px+1]  += bdiff * w7;
                    }
                    if(ny < height) {
                        if(px > 0) {
                            nextErrorRed[px-1]   += rdiff * w3;
                            nextErrorGreen[px-1] += gdiff * w3;
                            nextErrorBlue[px-1]  += bdiff * w3;
                        }
                        if(px < w - 1) {
                            nextErrorRed[px+1]   += rdiff * w1;
                            nextErrorGreen[px+1] += gdiff * w1;
                            nextErrorBlue[px+1]  += bdiff * w1;
                        }
                        nextErrorRed[px]   += rdiff * w5;
                        nextErrorGreen[px] += gdiff * w5;
                        nextErrorBlue[px]  += bdiff * w5;
                    }
                    i++;
                }
            }
        }
    }

    protected void analyzeWoven() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        final float populationBias = palette.populationBias;
        float w1 = (float) (20f * Math.sqrt(ditherStrength) * populationBias * populationBias * populationBias * populationBias), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = 48f * ditherStrength / (populationBias * populationBias * populationBias * populationBias),
                limit = 5f + 130f / (float)Math.sqrt(palette.colorCount+1.5f);

        float[] curErrorRed = new float[w], nextErrorRed = new float[w],
                curErrorGreen = new float[w], nextErrorGreen = new float[w],
                curErrorBlue = new float[w], nextErrorBlue = new float[w];

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0f);
            Arrays.fill(nextErrorGreen, 0f);
            Arrays.fill(nextErrorBlue, 0f);

            int py = flipped + flipDir * y, ny = y + 1;
            for (int px = 0; px < width && i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    er = Math.min(Math.max(((((px+1) * 0xC13FA9A902A6328FL + (y+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorRed[px]);
                    eg = Math.min(Math.max(((((px+3) * 0xC13FA9A902A6328FL + (y-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorGreen[px]);
                    eb = Math.min(Math.max(((((px+2) * 0xC13FA9A902A6328FL + (y-4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorBlue[px]);

                    int rr = MathUtils.clamp((int)(((color >>> 24)       ) + er + 0.5f), 0, 0xFF);
                    int gg = MathUtils.clamp((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0, 0xFF);
                    int bb = MathUtils.clamp((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0, 0xFF);
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                    used = paletteArray[paletteIndex & 0xFF];
                    rdiff = (0x5p-10f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x5p-10f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x5p-10f * ((color>>>8&255)- (used>>>8&255)) );
                    if(px < w - 1) {
                        curErrorRed[px+1]   += rdiff * w7;
                        curErrorGreen[px+1] += gdiff * w7;
                        curErrorBlue[px+1]  += bdiff * w7;
                    }
                    if(ny < height) {
                        if(px > 0) {
                            nextErrorRed[px-1]   += rdiff * w3;
                            nextErrorGreen[px-1] += gdiff * w3;
                            nextErrorBlue[px-1]  += bdiff * w3;
                        }
                        if(px < w - 1) {
                            nextErrorRed[px+1]   += rdiff * w1;
                            nextErrorGreen[px+1] += gdiff * w1;
                            nextErrorBlue[px+1]  += bdiff * w1;
                        }
                        nextErrorRed[px]   += rdiff * w5;
                        nextErrorGreen[px] += gdiff * w5;
                        nextErrorBlue[px]  += bdiff * w5;
                    }
                    i++;
                }
            }
        }
    }

    protected void analyzeDodgy() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        float populationBias = palette.populationBias;
        final float w1 = 8f * ditherStrength,
                w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = 0.35f * ditherStrength / (populationBias * populationBias * populationBias),
                limit = 90f;

        float[] curErrorRed = new float[w], nextErrorRed = new float[w],
                curErrorGreen = new float[w], nextErrorGreen = new float[w],
                curErrorBlue = new float[w], nextErrorBlue = new float[w];

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0f);
            Arrays.fill(nextErrorGreen, 0f);
            Arrays.fill(nextErrorBlue, 0f);

            int py = flipped + flipDir * y, ny = y + 1;
            for (int px = 0; px < width && i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    er = Math.min(Math.max(((TRI_BLUE_NOISE  [(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorRed[px]);
                    eg = Math.min(Math.max(((TRI_BLUE_NOISE_B[(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorGreen[px]);
                    eb = Math.min(Math.max(((TRI_BLUE_NOISE_C[(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorBlue[px]);

                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                    used = paletteArray[paletteIndex & 0xFF];

                    rdiff = (0x5p-8f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x5p-8f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x5p-8f * ((color>>>8&255)- (used>>>8&255)) );
                    rdiff /= (0.5f + Math.abs(rdiff));
                    gdiff /= (0.5f + Math.abs(gdiff));
                    bdiff /= (0.5f + Math.abs(bdiff));

                    if(px < w - 1) {
                        curErrorRed[px+1]   += rdiff * w7;
                        curErrorGreen[px+1] += gdiff * w7;
                        curErrorBlue[px+1]  += bdiff * w7;
                    }
                    if(ny < height) {
                        if(px > 0) {
                            nextErrorRed[px-1]   += rdiff * w3;
                            nextErrorGreen[px-1] += gdiff * w3;
                            nextErrorBlue[px-1]  += bdiff * w3;
                        }
                        if(px < w - 1) {
                            nextErrorRed[px+1]   += rdiff * w1;
                            nextErrorGreen[px+1] += gdiff * w1;
                            nextErrorBlue[px+1]  += bdiff * w1;
                        }
                        nextErrorRed[px]   += rdiff * w5;
                        nextErrorGreen[px] += gdiff * w5;
                        nextErrorBlue[px]  += bdiff * w5;
                    }
                    i++;
                }
            }
        }
    }

    protected void analyzeNeue() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        float rdiff, gdiff, bdiff;
        float er, eg, eb, adj;
        byte paletteIndex;
        final float populationBias = palette.populationBias;
        final float w1 = ditherStrength * 8f, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = (70f * ditherStrength / (populationBias * populationBias * populationBias)),
                limit = Math.min(127, (float) Math.pow(80, 1.635 - populationBias));

        float[] curErrorRed = new float[w], nextErrorRed = new float[w],
                curErrorGreen = new float[w], nextErrorGreen = new float[w],
                curErrorBlue = new float[w], nextErrorBlue = new float[w];

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0f);
            Arrays.fill(nextErrorGreen, 0f);
            Arrays.fill(nextErrorBlue, 0f);

            int py = flipped + flipDir * y, ny = y + 1;
            for (int px = 0; px < width && i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    adj = ((TRI_BLUE_NOISE[(px & 63) | (py & 63) << 6] + 0.5f) * 0.005f);
                    adj = Math.min(Math.max(adj * strength, -limit), limit);
                    er = adj + (curErrorRed[px]);
                    eg = adj + (curErrorGreen[px]);
                    eb = adj + (curErrorBlue[px]);
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                    used = paletteArray[paletteIndex & 0xFF];
                    rdiff = (0x2.Ep-8f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x2.Ep-8f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x2.Ep-8f * ((color>>>8&255)- (used>>>8&255)) );
                    rdiff *= 1.25f / (0.25f + Math.abs(rdiff));
                    gdiff *= 1.25f / (0.25f + Math.abs(gdiff));
                    bdiff *= 1.25f / (0.25f + Math.abs(bdiff));
                    if(px < w - 1) {
                        curErrorRed[px+1]   += rdiff * w7;
                        curErrorGreen[px+1] += gdiff * w7;
                        curErrorBlue[px+1]  += bdiff * w7;
                    }
                    if(ny < height) {
                        if(px > 0) {
                            nextErrorRed[px-1]   += rdiff * w3;
                            nextErrorGreen[px-1] += gdiff * w3;
                            nextErrorBlue[px-1]  += bdiff * w3;
                        }
                        if(px < w - 1) {
                            nextErrorRed[px+1]   += rdiff * w1;
                            nextErrorGreen[px+1] += gdiff * w1;
                            nextErrorBlue[px+1]  += bdiff * w1;
                        }
                        nextErrorRed[px]   += rdiff * w5;
                        nextErrorGreen[px] += gdiff * w5;
                        nextErrorBlue[px]  += bdiff * w5;
                    }
                    i++;
                }
            }
        }
    }

    protected void analyzeOverboard() {
        final int nPix = indexedPixels.length;
        int flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        final float populationBias = palette.populationBias;
        final float strength = ditherStrength * 1.5f * (populationBias * populationBias),
                noiseStrength = 4f / (populationBias * populationBias),
                limit = 110f;

        float[] curErrorRed = new float[w], nextErrorRed = new float[w],
                curErrorGreen = new float[w], nextErrorGreen = new float[w],
                curErrorBlue = new float[w], nextErrorBlue = new float[w];

        for (int by = 0, y = flipped, i = 0; by < height && i < nPix; by++, y += flipDir) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0f);
            Arrays.fill(nextErrorGreen, 0f);
            Arrays.fill(nextErrorBlue, 0f);

            for (int x = 0; x < width && i < nPix; x++) {
                int color = image.getPixel(x, y);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    float er = 0f, eg = 0f, eb = 0f;
                    switch ((x << 1 & 2) | (by & 1)){
                        case 0:
                            er += ((x ^ by) % 9 - 4);
                            er += ((x * 0xC13FA9A902A6328FL + by * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                            eg += (TRI_BLUE_NOISE_B[(x & 63) | (by & 63) << 6] + 0.5f) * 0x1p-5f;
                            eg += ((x * -0xC13FA9A902A6328FL + by * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                            eb += (TRI_BLUE_NOISE_C[(x & 63) | (by & 63) << 6] + 0.5f) * 0x1p-6f;
                            eb += ((by * 0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-20f;
                            break;
                        case 1:
                            er += (TRI_BLUE_NOISE[(x & 63) | (by & 63) << 6] + 0.5f) * 0x1p-5f;
                            er += ((x * -0xC13FA9A902A6328FL + by * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                            eg += (TRI_BLUE_NOISE_B[(x & 63) | (by & 63) << 6] + 0.5f) * 0x1p-6f;
                            eg += ((by * 0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-20f;
                            eb += ((x ^ by) % 11 - 5);
                            eb += ((by * -0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-21f;
                            break;
                        case 2:
                            er += (TRI_BLUE_NOISE[(x & 63) | (by & 63) << 6] + 0.5f) * 0x1p-6f;
                            er += ((by * 0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-20f;
                            eg += ((x ^ by) % 11 - 5);
                            eg += ((by * -0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-21f;
                            eb += ((x ^ by) % 9 - 4);
                            eb += ((x * 0xC13FA9A902A6328FL + by * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                            break;
                        default: // case 3:
                            er += ((x ^ by) % 11 - 5);
                            er += ((by * -0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-21f;
                            eg += ((x ^ by) % 9 - 4);
                            eg += ((x * 0xC13FA9A902A6328FL + by * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                            eb += (TRI_BLUE_NOISE_C[(x & 63) | (by & 63) << 6] + 0.5f) * 0x1p-5f;
                            eb += ((x * -0xC13FA9A902A6328FL + by * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                            break;
                    }
                    er = er * noiseStrength + curErrorRed[x];
                    eg = eg * noiseStrength + curErrorGreen[x];
                    eb = eb * noiseStrength + curErrorBlue[x];
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + Math.min(Math.max(er, -limit), limit), 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + Math.min(Math.max(eg, -limit), limit), 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + Math.min(Math.max(eb, -limit), limit), 0), 1023)] & 255;
                    byte paletteIndex;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                    int used = paletteArray[paletteIndex & 0xFF];
                    float rdiff = ((color >>> 24) - (used >>> 24)) * strength;
                    float gdiff = ((color >>> 16 & 255) - (used >>> 16 & 255)) * strength;
                    float bdiff = ((color >>> 8 & 255) - (used >>> 8 & 255)) * strength;
                    float r1 = rdiff * 16f / (45f + Math.abs(rdiff));
                    float g1 = gdiff * 16f / (45f + Math.abs(gdiff));
                    float b1 = bdiff * 16f / (45f + Math.abs(bdiff));
                    float r2 = r1 + r1;
                    float g2 = g1 + g1;
                    float b2 = b1 + b1;
                    float r4 = r2 + r2;
                    float g4 = g2 + g2;
                    float b4 = b2 + b2;
                    if(x < width - 1) {
                        curErrorRed[x+1]   += r4;
                        curErrorGreen[x+1] += g4;
                        curErrorBlue[x+1]  += b4;
                        if(x < width - 2) {
                            curErrorRed[x+2]   += r2;
                            curErrorGreen[x+2] += g2;
                            curErrorBlue[x+2]  += b2;
                        }
                    }
                    if(by+1 < height) {
                        if(x > 0) {
                            nextErrorRed[x-1]   += r2;
                            nextErrorGreen[x-1] += g2;
                            nextErrorBlue[x-1]  += b2;
                            if(x > 1) {
                                nextErrorRed[x-2]   += r1;
                                nextErrorGreen[x-2] += g1;
                                nextErrorBlue[x-2]  += b1;
                            }
                        }
                        nextErrorRed[x]   += r4;
                        nextErrorGreen[x] += g4;
                        nextErrorBlue[x]  += b4;
                        if(x < width - 1) {
                            nextErrorRed[x+1]   += r2;
                            nextErrorGreen[x+1] += g2;
                            nextErrorBlue[x+1]  += b2;
                            if(x < width - 2) {
                                nextErrorRed[x+2]   += r1;
                                nextErrorGreen[x+2] += g1;
                                nextErrorBlue[x+2]  += b1;
                            }
                        }
                    }
                    i++;
                }
            }
        }
    }

    protected void analyzeBurkes() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        byte paletteIndex;
        float r4, r2, r1, g4, g2, g1, b4, b2, b1;
        final float populationBias = palette.populationBias;
        final float s = (0.13f * ditherStrength / (populationBias * populationBias)),
                strength = s * 0.58f / (0.3f + s);
        
        float[] curErrorRed = new float[w], nextErrorRed = new float[w],
                curErrorGreen = new float[w], nextErrorGreen = new float[w],
                curErrorBlue = new float[w], nextErrorBlue = new float[w];

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0f);
            Arrays.fill(nextErrorGreen, 0f);
            Arrays.fill(nextErrorBlue, 0f);

            int py = flipped + flipDir * y, ny = y + 1;

            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    float er = curErrorRed[px];
                    float eg = curErrorGreen[px];
                    float eb = curErrorBlue[px];
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                    used = paletteArray[paletteIndex & 0xFF];
                    int rdiff = (color >>> 24) - (used >>> 24);
                    int gdiff = (color >>> 16 & 255) - (used >>> 16 & 255);
                    int bdiff = (color >>> 8 & 255) - (used >>> 8 & 255);
                    r1 = rdiff * strength;
                    g1 = gdiff * strength;
                    b1 = bdiff * strength;
                    r2 = r1 + r1;
                    g2 = g1 + g1;
                    b2 = b1 + b1;
                    r4 = r2 + r2;
                    g4 = g2 + g2;
                    b4 = b2 + b2;
                    if(px < w - 1) {
                        curErrorRed[px+1]   += r4;
                        curErrorGreen[px+1] += g4;
                        curErrorBlue[px+1]  += b4;
                        if(px < w - 2) {
                            curErrorRed[px+2]   += r2;
                            curErrorGreen[px+2] += g2;
                            curErrorBlue[px+2]  += b2;
                        }
                    }
                    if(ny < height) {
                        if(px > 0) {
                            nextErrorRed[px-1]   += r2;
                            nextErrorGreen[px-1] += g2;
                            nextErrorBlue[px-1]  += b2;
                            if(px > 1) {
                                nextErrorRed[px-2]   += r1;
                                nextErrorGreen[px-2] += g1;
                                nextErrorBlue[px-2]  += b1;
                            }
                        }
                        nextErrorRed[px]   += r4;
                        nextErrorGreen[px] += g4;
                        nextErrorBlue[px]  += b4;
                        if(px < w - 1) {
                            nextErrorRed[px+1]   += r2;
                            nextErrorGreen[px+1] += g2;
                            nextErrorBlue[px+1]  += b2;
                            if(px < w - 2) {
                                nextErrorRed[px+2]   += r1;
                                nextErrorGreen[px+2] += g1;
                                nextErrorBlue[px+2]  += b1;
                            }
                        }
                    }
                    i++;
                }
            }
        }
    }

    protected void analyzeOceanic() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;
        final float[] noise = TRI_BLUE_NOISE_MULTIPLIERS;

        final int w = width;
        byte paletteIndex;
        float r4, r2, r1, g4, g2, g1, b4, b2, b1;
        final float populationBias = palette.populationBias;
        final float s = (0.13f * ditherStrength / (populationBias * populationBias)),
                strength = s * 0.58f / (0.3f + s);
        
        float[] curErrorRed = new float[w], nextErrorRed = new float[w],
                curErrorGreen = new float[w], nextErrorGreen = new float[w],
                curErrorBlue = new float[w], nextErrorBlue = new float[w];

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0f);
            Arrays.fill(nextErrorGreen, 0f);
            Arrays.fill(nextErrorBlue, 0f);

            int py = flipped + flipDir * y, ny = y + 1;

            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    float er = curErrorRed[px];
                    float eg = curErrorGreen[px];
                    float eb = curErrorBlue[px];
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                    used = paletteArray[paletteIndex & 0xFF];
                    int rdiff = (color >>> 24) - (used >>> 24);
                    int gdiff = (color >>> 16 & 255) - (used >>> 16 & 255);
                    int bdiff = (color >>> 8 & 255) - (used >>> 8 & 255);
                    r1 = rdiff * strength;
                    g1 = gdiff * strength;
                    b1 = bdiff * strength;
                    r2 = r1 + r1;
                    g2 = g1 + g1;
                    b2 = b1 + b1;
                    r4 = r2 + r2;
                    g4 = g2 + g2;
                    b4 = b2 + b2;
                    float modifier;
                    if(px < w - 1) {
                        modifier = noise[(px + 1 & 63) | ((py << 6) & 0xFC0)];
                        curErrorRed[px+1]   += r4 * modifier;
                        curErrorGreen[px+1] += g4 * modifier;
                        curErrorBlue[px+1]  += b4 * modifier;
                        if(px < w - 2) {
                            modifier = noise[(px + 2 & 63) | ((py << 6) & 0xFC0)];
                            curErrorRed[px+2]   += r2 * modifier;
                            curErrorGreen[px+2] += g2 * modifier;
                            curErrorBlue[px+2]  += b2 * modifier;
                        }
                    }
                    if(ny < height) {
                        if(px > 0) {
                            modifier = noise[(px - 1 & 63) | ((ny << 6) & 0xFC0)];
                            nextErrorRed[px-1]   += r2 * modifier;
                            nextErrorGreen[px-1] += g2 * modifier;
                            nextErrorBlue[px-1]  += b2 * modifier;
                            if(px > 1) {
                                modifier = noise[(px - 2 & 63) | ((ny << 6) & 0xFC0)];
                                nextErrorRed[px-2]   += r1 * modifier;
                                nextErrorGreen[px-2] += g1 * modifier;
                                nextErrorBlue[px-2]  += b1 * modifier;
                            }
                        }
                        modifier = noise[(px & 63) | ((ny << 6) & 0xFC0)];
                        nextErrorRed[px]   += r4 * modifier;
                        nextErrorGreen[px] += g4 * modifier;
                        nextErrorBlue[px]  += b4 * modifier;
                        if(px < w - 1) {
                            modifier = noise[(px + 1 & 63) | ((ny << 6) & 0xFC0)];
                            nextErrorRed[px+1]   += r2 * modifier;
                            nextErrorGreen[px+1] += g2 * modifier;
                            nextErrorBlue[px+1]  += b2 * modifier;
                            if(px < w - 2) {
                                modifier = noise[(px + 2 & 63) | ((ny << 6) & 0xFC0)];
                                nextErrorRed[px+2]   += r1 * modifier;
                                nextErrorGreen[px+2] += g1 * modifier;
                                nextErrorBlue[px+2]  += b1 * modifier;
                            }
                        }
                    }
                    i++;
                }
            }
        }
    }

    protected void analyzeSeaside() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;
        final float[] noiseA = TRI_BLUE_NOISE_MULTIPLIERS;
        final float[] noiseB = TRI_BLUE_NOISE_MULTIPLIERS_B;
        final float[] noiseC = TRI_BLUE_NOISE_MULTIPLIERS_C;

        final int w = width;
        byte paletteIndex;
        final float populationBias = palette.populationBias;
        final float s = 0.15f * populationBias * ditherStrength,
                strength = s * 0.6f / (0.35f + s);
        
        float[] curErrorRed = new float[w], nextErrorRed = new float[w],
                curErrorGreen = new float[w], nextErrorGreen = new float[w],
                curErrorBlue = new float[w], nextErrorBlue = new float[w];

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0f);
            Arrays.fill(nextErrorGreen, 0f);
            Arrays.fill(nextErrorBlue, 0f);

            int py = flipped + flipDir * y, ny = y + 1;

            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    float er = curErrorRed[px];
                    float eg = curErrorGreen[px];
                    float eb = curErrorBlue[px];
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                    used = paletteArray[paletteIndex & 0xFF];
                    int rdiff = (color >>> 24) - (used >>> 24);
                    int gdiff = (color >>> 16 & 255) - (used >>> 16 & 255);
                    int bdiff = (color >>> 8 & 255) - (used >>> 8 & 255);
                    int modifier = ((px & 63) | (py << 6 & 0xFC0));
                    final float r1 = rdiff * strength * noiseA[modifier];
                    final float g1 = gdiff * strength * noiseB[modifier];
                    final float b1 = bdiff * strength * noiseC[modifier];
                    final float r2 = r1 + r1;
                    final float g2 = g1 + g1;
                    final float b2 = b1 + b1;
                    final float r4 = r2 + r2;
                    final float g4 = g2 + g2;
                    final float b4 = b2 + b2;

                    if(px < w - 1) {
                        modifier = ((px + 1 & 63) | (py << 6 & 0xFC0));
                        curErrorRed[px+1]   += r4 * noiseA[modifier];
                        curErrorGreen[px+1] += g4 * noiseB[modifier];
                        curErrorBlue[px+1]  += b4 * noiseC[modifier];
                        if(px < w - 2) {
                            modifier = ((px + 2 & 63) | ((py << 6) & 0xFC0));
                            curErrorRed[px+2]   += r2 * noiseA[modifier];
                            curErrorGreen[px+2] += g2 * noiseB[modifier];
                            curErrorBlue[px+2]  += b2 * noiseC[modifier];
                        }
                    }
                    if(ny < height) {
                        if(px > 0) {
                            modifier = (px - 1 & 63) | ((ny << 6) & 0xFC0);
                            nextErrorRed[px-1]   += r2 * noiseA[modifier];
                            nextErrorGreen[px-1] += g2 * noiseB[modifier];
                            nextErrorBlue[px-1]  += b2 * noiseC[modifier];
                            if(px > 1) {
                                modifier = (px - 2 & 63) | ((ny << 6) & 0xFC0);
                                nextErrorRed[px-2]   += r1 * noiseA[modifier];
                                nextErrorGreen[px-2] += g1 * noiseB[modifier];
                                nextErrorBlue[px-2]  += b1 * noiseC[modifier];
                            }
                        }
                        modifier = (px & 63) | ((ny << 6) & 0xFC0);
                        nextErrorRed[px]   += r4 * noiseA[modifier];
                        nextErrorGreen[px] += g4 * noiseB[modifier];
                        nextErrorBlue[px]  += b4 * noiseC[modifier];
                        if(px < w - 1) {
                            modifier = (px + 1 & 63) | ((ny << 6) & 0xFC0);
                            nextErrorRed[px+1]   += r2 * noiseA[modifier];
                            nextErrorGreen[px+1] += g2 * noiseB[modifier];
                            nextErrorBlue[px+1]  += b2 * noiseC[modifier];
                            if(px < w - 2) {
                                nextErrorRed[px+2]   += r1 * noiseA[modifier];
                                nextErrorGreen[px+2] += g1 * noiseB[modifier];
                                nextErrorBlue[px+2]  += b1 * noiseC[modifier];
                            }
                        }
                    }
                    i++;
                }
            }
        }
    }

    protected void analyzeMarten() {
        final int nPix = indexedPixels.length;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = palette.paletteArray[0] == 0;

        final float str = 45f * ditherStrength * (palette.colorCount <= 128
                ? MathUtils.map(6, 180f, 3.15f, 1f, palette.colorCount)
                : MathUtils.map(128f, 256f, 1.6425288f, 1f, palette.colorCount));

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                int color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    final float theta = ((px * 142 + y * 79 & 255) * 0x1p-8f);
                    int rr = fromLinearLUT[(int) Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + OtherMath.triangleWave(theta         ) * str, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int) Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + OtherMath.triangleWave(theta + 0.382f) * str, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int) Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + OtherMath.triangleWave(theta + 0.618f) * str, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeWren() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        final float populationBias = palette.populationBias;
        float partialDitherStrength = (0.5f * ditherStrength / (populationBias * populationBias)),
                strength = (80f * ditherStrength / (populationBias * populationBias)),
                blueStrength = (0.3f * ditherStrength / (populationBias * populationBias)),
                limit = 5f + 200f / (float)Math.sqrt(palette.colorCount+1.5f),
                r1, g1, b1, r2, g2, b2, r4, g4, b4;

        float[] curErrorRed = new float[w], nextErrorRed = new float[w],
                curErrorGreen = new float[w], nextErrorGreen = new float[w],
                curErrorBlue = new float[w], nextErrorBlue = new float[w];

        for (int by = 0, i = 0; by < height && i < nPix; by++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0f);
            Arrays.fill(nextErrorGreen, 0f);
            Arrays.fill(nextErrorBlue, 0f);

            int y = flipped + flipDir * by;
            for (int x = 0; x < width && i < nPix; x++) {
                color = image.getPixel(x, y);
                if (hasTransparent && (color & 0x80) == 0)
                    indexedPixels[i++] = 0;
                else {
                    er = Math.min(Math.max(( ( (TRI_BLUE_NOISE  [(x & 63) | (y & 63) << 6] + 0.5f) * blueStrength + ((((x+1) * 0xC13FA9A902A6328FL + (y+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorRed[x]);
                    eg = Math.min(Math.max(( ( (TRI_BLUE_NOISE_B[(x & 63) | (y & 63) << 6] + 0.5f) * blueStrength + ((((x+3) * 0xC13FA9A902A6328FL + (y-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorGreen[x]);
                    eb = Math.min(Math.max(( ( (TRI_BLUE_NOISE_C[(x & 63) | (y & 63) << 6] + 0.5f) * blueStrength + ((((x+2) * 0xC13FA9A902A6328FL + (y-4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorBlue[x]);

                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                    used = paletteArray[paletteIndex & 0xFF];
                    rdiff = ((color>>>24)-    (used>>>24))     * partialDitherStrength;
                    gdiff = ((color>>>16&255)-(used>>>16&255)) * partialDitherStrength;
                    bdiff = ((color>>>8&255)- (used>>>8&255))  * partialDitherStrength;

                    r1 = rdiff * 16f / (float)Math.sqrt(2048f + rdiff * rdiff);
                    g1 = gdiff * 16f / (float)Math.sqrt(2048f + gdiff * gdiff);
                    b1 = bdiff * 16f / (float)Math.sqrt(2048f + bdiff * bdiff);
                    r2 = r1 + r1;
                    g2 = g1 + g1;
                    b2 = b1 + b1;
                    r4 = r2 + r2;
                    g4 = g2 + g2;
                    b4 = b2 + b2;
                    if(x < w - 1) {
                        curErrorRed[x+1]   += r4;
                        curErrorGreen[x+1] += g4;
                        curErrorBlue[x+1]  += b4;
                        if(x < w - 2) {
                            curErrorRed[x+2]   += r2;
                            curErrorGreen[x+2] += g2;
                            curErrorBlue[x+2]  += b2;
                        }
                    }
                    if(by+1 < height) {
                        if(x > 0) {
                            nextErrorRed[x-1]   += r2;
                            nextErrorGreen[x-1] += g2;
                            nextErrorBlue[x-1]  += b2;
                            if(x > 1) {
                                nextErrorRed[x-2]   += r1;
                                nextErrorGreen[x-2] += g1;
                                nextErrorBlue[x-2]  += b1;
                            }
                        }
                        nextErrorRed[x]   += r4;
                        nextErrorGreen[x] += g4;
                        nextErrorBlue[x]  += b4;
                        if(x < w - 1) {
                            nextErrorRed[x+1]   += r2;
                            nextErrorGreen[x+1] += g2;
                            nextErrorBlue[x+1]  += b2;
                            if(x < w - 2) {
                                nextErrorRed[x+2]   += r1;
                                nextErrorGreen[x+2] += g1;
                                nextErrorBlue[x+2]  += b1;
                            }
                        }
                    }
                    i++;
                }
            }
        }
    }
}
