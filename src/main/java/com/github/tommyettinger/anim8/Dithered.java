/*
 * Copyright (c) 2022  Tommy Ettinger
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package com.github.tommyettinger.anim8;

/**
 * A renderer and/or writer that allows selecting a {@link DitherAlgorithm} for its output.
 * <br>
 * Created by Tommy Ettinger on 6/6/2020.
 */
public interface Dithered {

    /**
     * Gets the PaletteReducer this uses to lower the color count in an image. If the PaletteReducer is null, this
     * should try to assign itself a PaletteReducer when given a new image.
     * @return the PaletteReducer this uses; may be null
     */
    PaletteReducer getPalette();

    /**
     * Sets the PaletteReducer this uses to bring a high-color or different-palette image down to a smaller palette
     * size. If {@code palette} is null, this should try to assign itself a PaletteReducer when given a new image.
     * @param palette a PaletteReducer that is often pre-configured with a specific palette; null is usually allowed
     */
    void setPalette(PaletteReducer palette);
    
    /**
     * Gets the {@link DitherAlgorithm} this is currently using.
     * @return which dithering algorithm this currently uses.
     */
    DitherAlgorithm getDitherAlgorithm();
    /**
     * Sets the dither algorithm (or disables it) using an enum constant from {@link DitherAlgorithm}. If this is given
     * null, it instead does nothing.
     * @param ditherAlgorithm which {@link DitherAlgorithm} to use for upcoming output
     */
    void setDitherAlgorithm(DitherAlgorithm ditherAlgorithm);

    /**
     * Represents a choice of dithering algorithm to apply when writing a high-color image with a color-limited format.
     * Options are NONE (just using solid blocks of the closest color), GRADIENT_NOISE (using an edit on Jorge Jimenez'
     * Gradient Interleaved Noise, a kind of ordered dither that adds some visual noise to break up patterns), PATTERN
     * (Thomas Knoll's Pattern Dithering, with some gamma correction applied), DIFFUSION (an error-diffusing dither
     * using Floyd-Steinberg, which isn't optimal for animations but is very good for still images), BLUE_NOISE (an
     * ordered dither that corrects mismatched colors by checking a blue noise texture with no noticeable large
     * patterns, and also using a quasi-random pattern to further break up artifacts), CHAOTIC_NOISE (which is like
     * BLUE_NOISE but makes each frame of an animation dither differently, which can look busy but also trick the eye
     * into seeing details over several frames), and SCATTER (which is similar to DIFFUSION but uses blue noise to
     * scatter overly-regular patterns around). While NONE, GRADIENT_NOISE, BLUE_NOISE, DIFFUSION, CHAOTIC_NOISE, and
     * SCATTER maintain the approximate lightness balance of the original image, PATTERN may slightly lighten mid-tones
     * to make the gradient smoother. All of these algorithms except DIFFUSION are suitable for animations; using
     * error-diffusion makes tiny changes in some frames disproportionately affect other pixels in those frames, which
     * is compounded by how DIFFUSION can have large sections of minor artifacts that become very noticeable when they
     * suddenly change between frames. Using SCATTER may be a good alternative to DIFFUSION for animations. NONE is
     * fastest, and PATTERN is slowest. GRADIENT_NOISE, BLUE_NOISE, DIFFUSION, CHAOTIC_NOISE, and SCATTER are
     * in-between.
     * <br>
     * Created by Tommy Ettinger on 6/6/2020.
     */
    enum DitherAlgorithm {
        /**
         * Doesn't dither at all; this generally looks bad unless the palette matches the colors in the image very
         * closely or exactly.
         */
        NONE("None"),
        /**
         * Jorge Jimenez' Gradient Interleaved Noise, modified slightly to use as an ordered dither here; this can have
         * subtle repetitive artifacts, but doesn't have different amounts of noise on different frames or different
         * parts of an image (which is a potential problem for {@link #DIFFUSION} and the other error-diffusion
         * dithers). There's a sometimes-noticeable diagonal line pattern in the results this produces, and in
         * animations, this pattern appears in the same place on every frame, which can be either desirable (small
         * changes won't snowball into big ones) or undesirable (it makes the pattern appear to be part of the image).
         * Although {@link #BLUE_NOISE} is mostly similar, it has a "spongy" artifact instead of the diagonal line
         * artifact this can have. {@link #BLUE_NOISE} does have less noticeable small-scale patterns, though, for many
         * input images. This handles gradients quite well. For pixel art, you may want to reduce the dither strength to
         * 0.5 or so.
         */
        GRADIENT_NOISE("GradientNoise"),
        /**
         * Thomas Knoll's Pattern Dither (with a 4x4 matrix), as originally described by Joel Yliluoma in
         * <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">this dithering article</a>. Pattern Dither was
         * patented until late 2019, so Yliluoma had to use an 8x8 matrix instead of the 4x4 used here; the 4x4 is much
         * faster to compute and doesn't have as many artifacts with large enough palettes. It's an ordered dither, like
         * {@link #GRADIENT_NOISE}, but isn't nearly as noisy (though it isn't noisy, it instead has regular
         * square-shaped artifacts, which are mostly noticeable with small palettes). Earlier versions of Pattern Dither
         * here had issues with lightness changing strangely based on dither strength, but these are mostly fixed now.
         * {@link #NEUE} is the current default; it does a better job at obscuring artifacts from dither, it maintains
         * lightness well, and it handles gradients without banding. Setting the dither strength with
         * {@link PaletteReducer#setDitherStrength(float)} can really change how strongly artifacts appear here, but
         * artifacts may be very hard to spot with a full 255-color palette.
         */
        PATTERN("Pattern"),
        /**
         * Floyd-Steinberg error-diffusion dithering; this is a good option for still images, and it's an OK option
         * for some animated images. It doesn't lighten the image like {@link #PATTERN}, while still preserving most
         * details on shapes, but small changes in one part of an animation will affect different frames very
         * differently (which makes this less well-suited for animations). It may look better even in an animation than
         * {@link #GRADIENT_NOISE}, depending on the animation, but this isn't often. Setting the dither strength with
         * {@link PaletteReducer#setDitherStrength(float)} can improve the results with DIFFUSION tremendously, but the
         * dither strength shouldn't go above about 1.5 or maybe 2.0 (this shows artifacts at higher strength).
         * {@link #SCATTER}, {@link #NEUE}, {@link #WOVEN}, and {@link #DODGY} are based on this, and are generally able
         * to break up visible artifacts that Floyd-Steinberg can have; all of those are now recommended over Diffusion.
         */
        DIFFUSION("Diffusion"),
        /**
         * This is an ordered dither that modifies any error in a pixel's color by using 3 blue-noise patterns for each
         * channel separately. The separate channels have their resulting positive or negative values added to the
         * pixel's channels. This uses triangular-mapped blue noise patterns, which means most of its values are in the
         * middle of its range and very few are at the extremely bright or dark. This yields closer results to
         * {@link #NONE} than other ordered dithers like {@link #GRADIENT_NOISE}; it preserves soft gradients reasonably
         * well, and it keeps lightness moderately-well, but it can look "noisier" than the other ordered dithers. For
         * reference, the blue noise texture this uses looks like
         * <a href="https://github.com/tommyettinger/MultiTileBlueNoise/blob/master/results/tri/64/blueTri64_0.png?raw=true">this small image</a>;
         * it looks different from a purely-random white noise texture because blue noise has no low frequencies in any
         * direction, while white noise has all frequencies in equal measure. This has been optimized for quality on
         * animations more so than on still images. Where error-diffusion dithers like {@link #NEUE} and
         * {@link #DIFFUSION} can have "swimming" artifacts where the dither moves around in-place, ordered dithers are
         * essentially immune to that type of artifact. Setting the dither strength with
         * {@link PaletteReducer#setDitherStrength(float)} has significant effect (it didn't do much in previous
         * versions), and raising it can improve depth and the appearance of some images when banding occurs.
         */
        BLUE_NOISE("BlueNoise"),
        /**
         * Very similar to {@link #BLUE_NOISE} for a still frame, albeit less orderly, but in an animation this will
         * change wildly from frame to frame, taking an ordered dither (one which uses the same blue noise texture that
         * {@link #BLUE_NOISE} does) and incorporating one of the qualities of an error-diffusion dither to make each
         * frame dither differently. This can look very good for moving images, but it is, true to its name, both
         * chaotic and noisy. It can make many images look "rough" and "scratchy." Setting the dither strength with
         * {@link PaletteReducer#setDitherStrength(float)} won't do much here, but the result will have more of a
         * regular blue-noise pattern when dither strength is very low, and small changes will be introduced as dither
         * strength approaches 1.
         */
        CHAOTIC_NOISE("ChaoticNoise"),
        /**
         * This tries to subtly alter the more rigidly-defined error-diffusion dither of {@link #DIFFUSION} with a small
         * amount of triangular-distributed blue noise, and unlike {@link #CHAOTIC_NOISE}, it doesn't introduce white
         * noise. This offers an excellent mix of shape preservation, color preservation, animation-compatibility, and
         * speed, and it was the default for a long time. Setting the dither strength to a low value makes this more
         * bold, with higher contrast, while setting the strength too high (above 1.5, or sometimes higher) can
         * introduce artifacts. This is only-just-okay at smooth gradient handling; {@link #NEUE} and {@link #DODGY} are
         * much better at that and otherwise similar.
         */
        SCATTER("Scatter"),
        /**
         * An error diffusion dither that mixes in ordered noise from a triangular-mapped blue noise texture; this is
         * one of the best-behaving dithers here when it comes to smooth gradients. The approach to blue noise here is
         * to add it to the pixel channels before calculating error diffusion for that pixel. This is different from
         * {@link #SCATTER} in only a few ways, but a main one is that Scatter multiplies the current error by a blue
         * noise value, where this adds in blue noise regardless of current error. The exact reason isn't clear, but
         * this is drastically better when dithering smooth gradients, and can avoid banding except for the very
         * smallest palettes. While {@link #BLUE_NOISE} is similarly good with smooth gradients, it has a hard time
         * preserving fine color information (lightness is kept by Blue_Noise, but hue and saturation aren't very well);
         * Neue preserves both. {@link #DODGY} is a potential successor to NEUE, and acts much like it except that it
         * changes each RGB component separately, using three different blue noise textures.
         * <br>
         * This is currently the default dither.
         */
        NEUE("Neue"),
        /**
         * An ordered dither built around the lightness-dispersing R2 point sequence, by Martin Roberts. This is
         * similar to {@link #GRADIENT_NOISE}; both add or subtract from the values at each pixel, but usually add a
         * very different value to each pixel than to any of its neighbors. A major difference between GRADIENT_NOISE
         * and this would be that instead of changing all RGB components at once, ROBERTS changes each component
         * separately, using shifted versions of the R2 sequence. Compared to GRADIENT_NOISE, this has better (more
         * faithful) reproduction of many colors, but may show some colors less easily. This is an ordered dither,
         * so it won't change what artifacts it shows across different frames of an animation (the behavior here is
         * usually desirable, but not always).
         */
        ROBERTS("Roberts"),
        /**
         * An error-diffusion dither much like {@link #NEUE}, except that it adds or subtracts a different error value
         * from each RGB channel, and that it uses translated copies of the R2 dither used by {@link #ROBERTS}, instead
         * of using blue noise in any way. Modifying each channel separately can help color quality a lot, especially if
         * dither strength is high. R2 dither tends to have very fine-grained artifacts that are hard to notice, but
         * using a different translation for red, green, and blue means that sometimes the artifacts align for two or
         * more channels repeatedly, forming somewhat-noticeable light and dark patterns that look like scales or dots.
         * The artifacts here are usually less obvious than the ones in {@link #NEUE} at the same dither strength. This
         * is an excellent choice for still images, especially those with small, varied palettes. It is not expected to
         * be as good for pixel-art animations as an ordered dither.
         */
        WOVEN("Woven"),
        /**
         * An error-diffusion dither that, like {@link #NEUE}, starts with {@link #DIFFUSION Floyd-Steinberg} dither and
         * adds in blue noise values to break up patterns. Unlike NEUE, but like {@link #WOVEN}, this adds different
         * noise values to the red, green, and blue channels. This last step significantly improves color accuracy, even
         * on small palettes, while avoiding repetitive artifacts like WOVEN has. This is probably not a great pick for
         * pixel-art animations, but can be good for some other GIFs in specific cases; when GIFs are recompressed, and
         * they use ordered dithers, the artifacts can worsen, but an error-diffusion dither can move around artifacts
         * in things like videos converted to GIF such that any artifact lasts only one frame.
         * <br>
         * This algorithm is similar to NEUE, but generally better. If no serious flaws are found with it, DODGY could
         * become the default algorithm here.
         */
        DODGY("Dodgy"),
        /**
         * An intentionally-low-fidelity ordered dither with obvious repeating 2x2 patterns on a regular grid.
         * This is meant for dithering higher-color-count pixel art to produce lower color counts, without using any
         * techniques that are too complex to be used effectively in hand-made pixel art. This may actually look simpler
         * than it would have to be to look hand-made, especially at low dither strength.
         * <br>
         * This is probably closest to {@link #PATTERN} in appearance, just because they both use a square grid, but
         * this is much faster to run and looks less intricate. They also use different grids.
         */
        LOAF("Loaf"),
        /**
         * An error-diffusion dither (like {@link #DIFFUSION}) that uses offset versions of the R2 sequence (like
         * {@link #WOVEN}) and different blue noise textures (like {@link #DODGY}). This is a very good dither in many
         * cases, and performs especially well on any-sized mid-to-low-saturation palettes. It tends to be able to
         * preserve both hue and lightness well without showing repetitive structural artifacts (like WOVEN does). The
         * main down-side to this is that in some cases, you may need to reduce or slightly increase dither strength
         * to either avoid horizontal-zig-zag-line artifacts, or to improve hue or lightness fidelity. These cases
         * aren't especially common, and working around this is as easy as calling
         * {@link PaletteReducer#setDitherStrength(float)} (or its counterpart for a Gif or PNG class).
         */
        WREN("Wren"),
        /**
         * Blue noise used to alter Burkes error diffusion while also using the R2 sequence. Very good, but has a slight
         * "dusty" look if the palette isn't well-matched.
         */
        BLUBBER("Blubber");

        /**
         * Used by {@link #toString()} to store a more human-readable name that isn't ALWAYS_YELLING.
         */
        public final String legibleName;

        /**
         * A cached array of the result of {@link #values()}, to avoid repeatedly allocating new
         * {@code DitherAlgorithm[]} arrays on each call to values().
         */
        public static final DitherAlgorithm[] ALL = values();

        DitherAlgorithm(String name){
            this.legibleName = name;
        }

        @Override
        public String toString() {
            return legibleName;
        }
    }
}
