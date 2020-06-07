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
     * Gradient Interleaved Noise, a kind of ordered dither that adds some visual noise to break up patterns), and PATTERN
     * (using a skewed variant on Thomas Knoll's Pattern Dithering, with some gamma correction applied). While NONE and
     * GRADIENT_NOISE maintain the approximate lightness balance of the original image, PATTERN may lighten mid-tones
     * somewhat to make the gradient smoother. All of these algorithms are suitable for animations; that is, they don't rely
     * on error diffusion between pixels. NONE is fastest, PATTERN is slowest, and GRADIENT_NOISE is in-between.
     * <br>
     * Created by Tommy Ettinger on 6/6/2020.
     */
    enum DitherAlgorithm {
        NONE, GRADIENT_NOISE, PATTERN
    }
}
