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
         * {@link #WREN} is the current default; it does a better job at obscuring artifacts from dither, it
         * maintains lightness well, and it handles gradients without banding. Setting the dither strength with
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
         * This is an ordered dither that modifies any error in a pixel's color by using a blue-noise pattern that
         * affects lightness. The noise has its resulting positive or negative values added to the all three of the
         * pixel's channels. This uses a triangular-mapped blue noise pattern, which means most of its values are in the
         * middle of its range and very few are at the extremely bright or dark. This yields closer results to
         * {@link #NONE} than other ordered dithers like {@link #GRADIENT_NOISE}; it preserves soft gradients reasonably
         * well, and it keeps lightness moderately-well, but it can look "noisier" than the other ordered dithers. A key
         * extra thing this does is to add a checkerboard pattern of light and dark pixels, which can be a noticeable
         * artifact with small palettes or high dither strength.
         * For reference, the blue noise texture this uses looks like
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
         * introduce artifacts. This is only-just-okay at smooth gradient handling; {@link #NEUE}, {@link #DODGY},
         * {@link #OVERBOARD}, {@link #WREN}, {@link #OCEANIC}, and {@link #SEASIDE} are much better at that and
         * otherwise similar.
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
         * changes each RGB component separately, using three different blue noise textures. DODGY is, however, more
         * chaotic-looking sometimes. There's always the current default dither, {@link #WREN}, which was inspired
         * by NEUE, DODGY, and {@link #WOVEN} to get a generally-good compromise.
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
         * An error-diffusion dither (like {@link #DIFFUSION}, but using Burkes instead of Floyd-Steinberg) that uses
         * offset versions of the R2 sequence (like {@link #WOVEN}) and different blue noise textures (like
         * {@link #DODGY}). This is a very good dither in many cases, and performs especially well on any-sized
         * mid-to-low-saturation palettes. It tends to be able to preserve both hue and lightness well without showing
         * repetitive structural artifacts (like WOVEN does). The main down-side to this is that in some cases, you may
         * need to reduce or slightly increase dither strength to either avoid horizontal-zig-zag-line artifacts, or to
         * improve hue or lightness fidelity. These cases aren't especially common, and working around this is as easy
         * as calling {@link PaletteReducer#setDitherStrength(float)} (or its counterpart for a Gif or PNG class). These
         * artifacts have gotten less frequent with some changes to the algorithm just after it was introduced.
         * <br>
         * This is currently the default dither.
         */
        WREN("Wren"),
        /**
         * An error-diffusion dither (like {@link #DIFFUSION}, but using Burkes instead of Floyd-Steinberg) that uses
         * an assortment of patterns to add error to diffuse, selecting which patterns to use in a way that mimics a
         * simple ordered dither. This looks a lot like {@link #WREN} in practice, but tends to have many patterns
         * conflicting with each other, adding more color noise but reducing some error-diffusion artifacts. Unlike
         * {@link #WREN} and {@link #WOVEN}, this won't usually add a "rough" or "canvas-like" appearance to parts of an
         * image that are mostly flat in color, though it may add other "regular" patterns. The main disadvantage of
         * this dithering algorithm is that it is more complex than most of the others here, so copying or editing it
         * would be more challenging. It doesn't appear to be much slower than {@link #WREN}, if it is slower at all.
         * <br>
         * Like {@link #WREN}, but unlike {@link #NEUE}, this adds extra error differently to different RGB channels.
         * It doesn't go quite as far as {@link #WREN} at allowing really tremendous changes in color, which does mean
         * it isn't always as capable of producing high-quality dithers with very small palettes. However, this tradeoff
         * also means it doesn't pick up low-quality artifacts when dither strength is high.
         * <br>
         * This dither is based on Burkes dither, but you could also just use {@link #BURKES} to avoid adding in any
         * extra noise. You could also use {@link #OCEANIC} to incorporate just a little noise, softly. Relative to
         * those two (newer) dithers, OVERBOARD has a harder time with "curt" gradients, that is, those that change
         * smoothly but very quickly, and quickly stop changing. It does do well with larger, more-free-form gradients.
         */
        OVERBOARD("Overboard"),
        /**
         * An error-diffusion dither like {@link #DIFFUSION}, but using Burkes dither instead of Floyd-Steinberg. This
         * can often look better than Floyd-Steinberg, at least how it is implemented here. This remains the case even
         * with fairly high dither strength. If you set the strength unreasonably high, this will slowly approach an
         * upper limit for how strong it can get, but will never reach it.
         * <br>
         * If you encounter issues with BURKES dither, you can try the very similar {@link #OCEANIC} dither instead. It
         * has almost the same code as BURKES, except that makes a small adjustment to every amount of error that gets
         * diffused, using blue noise to make the adjustment deterministic but quasi-random.
         * <br>
         * The source for this is very similar to the other error-diffusion algorithms in use here, and probably was
         * informed by <a href="https://tannerhelland.com/2012/12/28/dithering-eleven-algorithms-source-code.html">this blog post</a>
         * by Tanner Helland.
         */
        BURKES("Burkes"),
        /**
         * An error-diffusion dither based closely on {@link #BURKES}, but that modifies how much error gets diffused
         * using a per-pixel multiplier obtained from blue noise. Using noise to (usually slightly) adjust the error
         * makes some unpleasant artifacts in BURKES dither essentially disappear here, replaced with fuzzy sections.
         * This does well on soft lightness gradients, much like how {@link #NEUE} does, and is significantly better
         * than BURKES at this task. It adds some noise, but not nearly as much as {@link #NEUE}, {@link #DODGY},
         * {@link #SCATTER}, {@link #WREN}, or {@link #OVERBOARD}, while avoiding the repetitive artifacts in
         * {@link #ROBERTS}, {@link #WOVEN}, and {@link #PATTERN}.
         */
        OCEANIC("Oceanic"),
        /**
         * A close relative of {@link #OCEANIC}, this also incorporates noise into {@link #BURKES} to change how each
         * pixel diffuses error. Unlike OCEANIC, the noise is different for each channel, which can improve how well
         * this approximates colors with small palettes, but can make the dither look more "confetti-like" by making one
         * pixel more red, a neighbor more green, another nearby more blue, etc. This is the same technique used by
         * {@link #DODGY} to improve upon {@link #NEUE}, and various other newer dithering algorithms here also use it.
         * That technique does, however, make lightness not change as reliably as with {@link #OCEANIC}, which adds the
         * same amount of change to all RGB channels at once (making them all approach black or white).
         */
        SEASIDE("Seaside"),
        /**
         * A relative of {@link #LOAF}, this is another ordered dither, with comparable speed to and higher quality than
         * LOAF (but less of a "hand-drawn" feeling), and higher speed and comparable quality to {@link #PATTERN}.
         * This will have some grid-based artifacts, but because it uses a somewhat large 8x8 grid (as opposed to 2x2
         * for LOAF), their appearance isn't always as obvious. Like LOAF and PATTERN, this should look good for
         * animations, since it doesn't have the error-diffusion issues where diffused error can zigzag over a moving
         * object during an animation.
         */
        GOURD("Gourd"),
        /**
         * An ordered dither based on using Blue Noise with a Tent (or triangular-mapped) distribution. Specifically,
         * this uses a different triangular distribution for each channel, forming something like an octagon inside the
         * RGB cube where it can choose a dithered pixel. This tends to look similar to {@link #BLUE_NOISE}, but with
         * the checkerboard pattern weaker and the blue noise stronger. It is more likely to counterbalance when it
         * places many similar pixels by mingling a few very different pixels in with them.
         * <br>
         * The extra blue noise hits with a bit of "blunt force" compared to error diffusion dithers.
         */
        BLUNT("Blunt"),
        /**
         * An ordered dither that is very similar to {@link #BLUNT} but doesn't use a checkerboard directly, and instead
         * of using trianglar-mapped blue noise, uses a 128x128 triangular-mapped Bayer Matrix.  This produces a much
         * more "regular" and "grid-patterned" dither, while being less "scratchy" and "noisy". This is meant to look
         * better when dithering art that has more flat areas than gradients, but it turns out to handle gradients
         * fairly well, too. {@link #BLUNT}, on the other hand, will be better able to "synthesize" colors not
         * well-represented by the palette using clusters of different colors. This gives that feature up to handle
         * lightness changes more smoothly.
         * <br>
         * This uses a Tent distribution for its added error, and uses it with a Bayer Matrix, hence the name as a
         * rearrangement of Bayer and Tent.
         */
        BANTER("Banter"),
        /**
         * An ordered dither that works much like {@link #ROBERTS}, which gets per-channel uniform sub-random noise and
         * feeds it to {@link OtherMath#triangleWave(float)}, but adds less error when the palette is larger, and uses
         * interleaved gradient noise (which is also used by {@link #GRADIENT_NOISE}) instead of using the R2 sequence
         * as ROBERTS does. This should help the common case of using a high-quality 255-color palette to dither either
         * still images or videos/animations (because this is an ordered dither, it won't have error-diffusion's
         * problems with animated inputs).
         * <br>
         * The name comes partly from Dr. Martin Roberts (since this is based on ROBERTS dither) and partly from the
         * fluffy animal called a marten, which is fitting because the dither is much softer for large palettes here.
         */
        MARTEN("Marten");

        /**
         * Used by {@link #toString()} to store a more human-readable name that isn't ALWAYS_YELLING.
         */
        public final String legibleName;

        /**
         * A cached array of the result of {@link #values()}, to avoid repeatedly allocating new
         * {@code DitherAlgorithm[]} arrays on each call to values().
         */
        // currently (in version 0.4.5), this is:
// NONE, GRADIENT_NOISE, PATTERN, DIFFUSION, BLUE_NOISE, CHAOTIC_NOISE, SCATTER, NEUE, ROBERTS, WOVEN, DODGY, LOAF, WREN, OVERBOARD, BURKES, OCEANIC, SEASIDE, GOURD
// if alphabetized:
// BLUE_NOISE, BURKES, CHAOTIC_NOISE, DIFFUSION, DODGY, GOURD, GRADIENT_NOISE, LOAF, NEUE, NONE, OCEANIC, OVERBOARD, PATTERN, ROBERTS, SCATTER, SEASIDE, WOVEN, WREN
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
