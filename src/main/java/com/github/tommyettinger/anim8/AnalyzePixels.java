package com.github.tommyettinger.anim8;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.FloatArray;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

class AnalyzePixels {

    Integer idx;
    Pixmap image; // current frame

    Dithered.DitherAlgorithm ditherAlgorithm = Dithered.DitherAlgorithm.NEUE;
    int width; // image size
    int height;
    boolean flipY = true;
    PaletteReducer palette;
    float ditherStrength = 1f;
    boolean fastAnalysis = true;
    boolean[] usedEntry = new boolean[256]; // active palette entries
    int seq = 0;
    byte[] indexedPixels; // converted frame indexed to palette
    int colorDepth; // number of bit planes
    byte[] colorTab; // RGB palette, 3 bytes per color
    int palSize = 7; // color table size (bits-1)
    int transIndex = -1; // transparent index in color table

    public AnalyzePixels(Integer idx, Pixmap image) {
        this.idx = idx;
        this.image = image;
        seq = idx;
        this.width = image.getWidth();
        this.height = image.getHeight();
        //Logger.getGlobal().log(Level.INFO, "Analyze: IDX: " + idx);
        if (idx > 1) {
            palette = new PaletteReducer();
            palette.analyzeFast(image, 150, 256);
        } else
            palette = new PaletteReducer(image);
    }

    protected AnalyzedPixmap analyzePixels() {
        int nPix = width * height;
        indexedPixels = new byte[nPix];
        palette.setDitherStrength(ditherStrength);
        if (seq > 1) {
            if (fastAnalysis)
                palette.analyzeFast(image, 150, 256);
            else
                palette.analyze(image, 150, 256);
        }
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        colorTab = new byte[256 * 3]; // create reduced palette
        for (int i = 0, bi = 0; i < 256; i++) {
            int pa = paletteArray[i];
            colorTab[bi++] = (byte) (pa >>> 24);
            colorTab[bi++] = (byte) (pa >>> 16);
            colorTab[bi++] = (byte) (pa >>> 8);
            usedEntry[i] = false;
        }
        // map image pixels to new palette
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        boolean hasTransparent = paletteArray[0] == 0;
        switch (ditherAlgorithm) {
            case NONE: {
                for (int y = 0, i = 0; y < height && i < nPix; y++) {
                    for (int px = 0; px < width & i < nPix; px++) {
                        color = image.getPixel(px, flipped + flipDir * y);
                        if ((color & 0x80) == 0 && hasTransparent)
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
            break;
            case PATTERN: {
                int cr, cg, cb, usedIndex;
                final float errorMul = palette.ditherStrength * palette.populationBias;
                for (int y = 0, i = 0; y < height && i < nPix; y++) {
                    for (int px = 0; px < width & i < nPix; px++) {
                        color = image.getPixel(px, flipped + flipDir * y);
                        if ((color & 0x80) == 0 && hasTransparent)
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
                                palette.candidates[c | 16] = PaletteReducer.shrink(palette.candidates[c] = used = paletteArray[usedIndex]);
                                er += cr - (used >>> 24);
                                eg += cg - (used >>> 16 & 0xFF);
                                eb += cb - (used >>> 8 & 0xFF);
                            }
                            PaletteReducer.sort16(palette.candidates);
                            usedEntry[(indexedPixels[i] = (byte) palette.reverseMap.get(palette.candidates[
                                    PaletteReducer.thresholdMatrix16[((px & 3) | (y & 3) << 2)]], 1)
                            ) & 255] = true;
                            i++;

                        }
                    }
                }
            }
            break;
            case CHAOTIC_NOISE: {
                double adj, strength = palette.ditherStrength * palette.populationBias * 1.5;
                long s = 0xC13FA9A902A6328FL * seq;
                for (int y = 0, i = 0; y < height && i < nPix; y++) {
                    for (int px = 0; px < width & i < nPix; px++) {
                        color = image.getPixel(px, flipped + flipDir * y);
                        if ((color & 0x80) == 0 && hasTransparent)
                            indexedPixels[i++] = 0;
                        else {
                            int rr = ((color >>> 24));
                            int gg = ((color >>> 16) & 0xFF);
                            int bb = ((color >>> 8) & 0xFF);
                            used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))] & 0xFF];
                            adj = ((PaletteReducer.TRI_BLUE_NOISE[(px & 63) | (y & 63) << 6] + 0.5f) * 0.007843138f);
                            adj *= adj * adj;
                            //// Complicated... This starts with a checkerboard of -0.5 and 0.5, times a tiny fraction.
                            //// The next 3 lines generate 3 low-quality-random numbers based on s, which should be
                            ////   different as long as the colors encountered so far were different. The numbers can
                            ////   each be positive or negative, and are reduced to a manageable size, summed, and
                            ////   multiplied by the earlier tiny fraction. Summing 3 random values gives us a curved
                            ////   distribution, centered on about 0.0 and weighted so most results are close to 0.
                            ////   Two of the random numbers use an XLCG, and the last uses an LCG.
                            adj += ((px + y & 1) - 0.5f) * 0x1.8p-49 * strength *
                                    (((s ^ 0x9E3779B97F4A7C15L) * 0xC6BC279692B5CC83L >> 15) +
                                            ((~s ^ 0xDB4F0B9175AE2165L) * 0xD1B54A32D192ED03L >> 15) +
                                            ((s = (s ^ color) * 0xD1342543DE82EF95L + 0x91E10DA5C79E7B1DL) >> 15));
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
            break;
            case GRADIENT_NOISE: {
                float pos, adj;
                final float strength = palette.ditherStrength * palette.populationBias * 3;
                for (int y = 0, i = 0; y < height && i < nPix; y++) {
                    for (int px = 0; px < width & i < nPix; px++) {
                        color = image.getPixel(px, flipped + flipDir * y);
                        if ((color & 0x80) == 0 && hasTransparent)
                            indexedPixels[i++] = 0;
                        else {
                            color |= (color >>> 5 & 0x07070700) | 0xFE;
                            int rr = ((color >>> 24));
                            int gg = ((color >>> 16) & 0xFF);
                            int bb = ((color >>> 8) & 0xFF);
                            used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))] & 0xFF];
                            pos = (px * 0.06711056f + y * 0.00583715f);
                            pos -= (int) pos;
                            pos *= 52.9829189f;
                            pos -= (int) pos;
                            adj = (pos - 0.5f) * strength;
//                            adj = MathUtils.sin(pos * 2f - 1f) * strength;
//                            adj = (pos * pos - 0.3f) * strength;
                            rr = Math.min(Math.max((int) (rr + (adj * (rr - (used >>> 24)))), 0), 0xFF);
                            gg = Math.min(Math.max((int) (gg + (adj * (gg - (used >>> 16 & 0xFF)))), 0), 0xFF);
                            bb = Math.min(Math.max((int) (bb + (adj * (bb - (used >>> 8 & 0xFF)))), 0), 0xFF);
                            usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                            i++;
                        }
                    }
                }
            }
            break;
            case DIFFUSION: {
                final int w = width;
                float rdiff, gdiff, bdiff;
                float er, eg, eb;
                byte paletteIndex;
                float w1 = palette.ditherStrength * 4, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

                float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
                if (palette.curErrorRedFloats == null) {
                    curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                    nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                    curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                    nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                    curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                    nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
                } else {
                    curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                    nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                    curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                    nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                    curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                    nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);
                }

                for (int y = 0, i = 0; y < height && i < nPix; y++) {
                    System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                    System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                    System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);

                    int py = flipped + flipDir * y,
                            ny = y + 1;

                    for (int px = 0; px < width & i < nPix; px++) {
                        color = image.getPixel(px, py);
                        if ((color & 0x80) == 0 && hasTransparent)
                            indexedPixels[i++] = 0;
                        else {
                            er = curErrorRed[px];
                            eg = curErrorGreen[px];
                            eb = curErrorBlue[px];
                            int rr = Math.min(Math.max((int) (((color >>> 24)) + er + 0.5f), 0), 0xFF);
                            int gg = Math.min(Math.max((int) (((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                            int bb = Math.min(Math.max((int) (((color >>> 8) & 0xFF) + eb + 0.5f), 0), 0xFF);
                            usedEntry[(indexedPixels[i] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))]) & 255] = true;
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = OtherMath.cbrtShape(0x1.8p-8f * ((color >>> 24) - (used >>> 24)));
                            gdiff = OtherMath.cbrtShape(0x1.8p-8f * ((color >>> 16 & 255) - (used >>> 16 & 255)));
                            bdiff = OtherMath.cbrtShape(0x1.8p-8f * ((color >>> 8 & 255) - (used >>> 8 & 255)));
                            if (px < w - 1) {
                                curErrorRed[px + 1] += rdiff * w7;
                                curErrorGreen[px + 1] += gdiff * w7;
                                curErrorBlue[px + 1] += bdiff * w7;
                            }
                            if (ny < height) {
                                if (px > 0) {
                                    nextErrorRed[px - 1] += rdiff * w3;
                                    nextErrorGreen[px - 1] += gdiff * w3;
                                    nextErrorBlue[px - 1] += bdiff * w3;
                                }
                                if (px < w - 1) {
                                    nextErrorRed[px + 1] += rdiff * w1;
                                    nextErrorGreen[px + 1] += gdiff * w1;
                                    nextErrorBlue[px + 1] += bdiff * w1;
                                }
                                nextErrorRed[px] += rdiff * w5;
                                nextErrorGreen[px] += gdiff * w5;
                                nextErrorBlue[px] += bdiff * w5;
                            }
                            i++;
                        }
                    }
                }
            }
            break;
            case BLUE_NOISE: {
                float adj, strength = 24 * palette.ditherStrength / palette.populationBias;
                for (int y = 0, i = 0; y < height && i < nPix; y++) {
                    for (int px = 0; px < width & i < nPix; px++) {
                        color = image.getPixel(px, flipped + flipDir * y);
                        if ((color & 0x80) == 0 && hasTransparent)
                            indexedPixels[i++] = 0;
                        else {
                            int ti = (px & 63) | (y & 63) << 6;
                            float variation = (strength + 0x1.3p-5f * (PaletteReducer.TRI_BLUE_NOISE[ti] + 0.5f)) * 0.007f;
                            adj = ((PaletteReducer.TRI_BLUE_NOISE_D[ti] + 0.5f) * variation);
                            int rr = MathUtils.clamp((int) (adj + ((color >>> 24))), 0, 255);
                            adj = ((PaletteReducer.TRI_BLUE_NOISE_B[ti] + 0.5f) * variation);
                            int gg = MathUtils.clamp((int) (adj + ((color >>> 16) & 0xFF)), 0, 255);
                            adj = ((PaletteReducer.TRI_BLUE_NOISE_C[ti] + 0.5f) * variation);
                            int bb = MathUtils.clamp((int) (adj + ((color >>> 8) & 0xFF)), 0, 255);
                            usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                            i++;
                        }
                    }
                }
            }
            break;
            case SCATTER: {
                final int w = width;
                float rdiff, gdiff, bdiff;
                float er, eg, eb;
                byte paletteIndex;
                float w1 = palette.ditherStrength * 3.5f, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

                float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
                if (palette.curErrorRedFloats == null) {
                    curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                    nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                    curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                    nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                    curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                    nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
                } else {
                    curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                    nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                    curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                    nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                    curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                    nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);
                }

                for (int y = 0, i = 0; y < height && i < nPix; y++) {
                    System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                    System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                    System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);

                    int py = flipped + flipDir * y,
                            ny = y + 1;

                    for (int px = 0; px < width & i < nPix; px++) {
                        color = image.getPixel(px, py);
                        if ((color & 0x80) == 0 && hasTransparent)
                            indexedPixels[i++] = 0;
                        else {
                            float tbn = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS[(px & 63) | ((y << 6) & 0xFC0)];
                            er = curErrorRed[px] * tbn;
                            eg = curErrorGreen[px] * tbn;
                            eb = curErrorBlue[px] * tbn;
                            int rr = Math.min(Math.max((int) (((color >>> 24)) + er + 0.5f), 0), 0xFF);
                            int gg = Math.min(Math.max((int) (((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                            int bb = Math.min(Math.max((int) (((color >>> 8) & 0xFF) + eb + 0.5f), 0), 0xFF);
                            usedEntry[(indexedPixels[i] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))]) & 255] = true;
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = OtherMath.cbrtShape(0x2.Ep-8f * ((color >>> 24) - (used >>> 24)));
                            gdiff = OtherMath.cbrtShape(0x2.Ep-8f * ((color >>> 16 & 255) - (used >>> 16 & 255)));
                            bdiff = OtherMath.cbrtShape(0x2.Ep-8f * ((color >>> 8 & 255) - (used >>> 8 & 255)));
                            if (px < w - 1) {
                                curErrorRed[px + 1] += rdiff * w7;
                                curErrorGreen[px + 1] += gdiff * w7;
                                curErrorBlue[px + 1] += bdiff * w7;
                            }
                            if (ny < height) {
                                if (px > 0) {
                                    nextErrorRed[px - 1] += rdiff * w3;
                                    nextErrorGreen[px - 1] += gdiff * w3;
                                    nextErrorBlue[px - 1] += bdiff * w3;
                                }
                                if (px < w - 1) {
                                    nextErrorRed[px + 1] += rdiff * w1;
                                    nextErrorGreen[px + 1] += gdiff * w1;
                                    nextErrorBlue[px + 1] += bdiff * w1;
                                }
                                nextErrorRed[px] += rdiff * w5;
                                nextErrorGreen[px] += gdiff * w5;
                                nextErrorBlue[px] += bdiff * w5;
                            }
                            i++;
                        }
                    }
                }
            }
            break;
            default:
            case NEUE: {
                final int w = width;
                float rdiff, gdiff, bdiff;
                float er, eg, eb;
                byte paletteIndex;
                float w1 = palette.ditherStrength * 7f, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                        adj, strength = (32f * palette.ditherStrength / (palette.populationBias * palette.populationBias)),
                        limit = (float) Math.pow(80, 1.635 - palette.populationBias);

                float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
                if (palette.curErrorRedFloats == null) {
                    curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                    nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                    curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                    nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                    curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                    nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
                } else {
                    curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                    nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                    curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                    nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                    curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                    nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);
                }

                for (int y = 0, i = 0; y < height && i < nPix; y++) {
                    System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                    System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                    System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);

                    int py = flipped + flipDir * y,
                            ny = y + 1;
                    for (int px = 0; px < width && i < nPix; px++) {
                        color = image.getPixel(px, py);
                        if ((color & 0x80) == 0 && hasTransparent)
                            indexedPixels[i++] = 0;
                        else {
                            adj = ((PaletteReducer.TRI_BLUE_NOISE[(px & 63) | (py & 63) << 6] + 0.5f) * 0.005f); // plus or minus 255/400
                            adj = Math.min(Math.max(adj * strength, -limit), limit);
                            er = adj + (curErrorRed[px]);
                            eg = adj + (curErrorGreen[px]);
                            eb = adj + (curErrorBlue[px]);

                            int rr = MathUtils.clamp((int) (((color >>> 24)) + er + 0.5f), 0, 0xFF);
                            int gg = MathUtils.clamp((int) (((color >>> 16) & 0xFF) + eg + 0.5f), 0, 0xFF);
                            int bb = MathUtils.clamp((int) (((color >>> 8) & 0xFF) + eb + 0.5f), 0, 0xFF);
                            usedEntry[(indexedPixels[i] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))]) & 255] = true;
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = OtherMath.cbrtShape(0x1.7p-10f * ((color >>> 24) - (used >>> 24)));
                            gdiff = OtherMath.cbrtShape(0x1.7p-10f * ((color >>> 16 & 255) - (used >>> 16 & 255)));
                            bdiff = OtherMath.cbrtShape(0x1.7p-10f * ((color >>> 8 & 255) - (used >>> 8 & 255)));
                            if (px < w - 1) {
                                curErrorRed[px + 1] += rdiff * w7;
                                curErrorGreen[px + 1] += gdiff * w7;
                                curErrorBlue[px + 1] += bdiff * w7;
                            }
                            if (ny < height) {
                                if (px > 0) {
                                    nextErrorRed[px - 1] += rdiff * w3;
                                    nextErrorGreen[px - 1] += gdiff * w3;
                                    nextErrorBlue[px - 1] += bdiff * w3;
                                }
                                if (px < w - 1) {
                                    nextErrorRed[px + 1] += rdiff * w1;
                                    nextErrorGreen[px + 1] += gdiff * w1;
                                    nextErrorBlue[px + 1] += bdiff * w1;
                                }
                                nextErrorRed[px] += rdiff * w5;
                                nextErrorGreen[px] += gdiff * w5;
                                nextErrorBlue[px] += bdiff * w5;
                            }
                            i++;
                        }
                    }
                }
            }
            break;
        }
        colorDepth = 8;
        palSize = 7;
        // get the closest match to transparent color if specified
        if (hasTransparent) {
            transIndex = 0;
        }
        return new AnalyzedPixmap(idx, colorTab, indexedPixels);
    }
}
