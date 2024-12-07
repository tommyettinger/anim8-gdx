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

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.*;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

import static com.github.tommyettinger.anim8.ConstantData.ENCODED_SNUGGLY;

/**
 * Data that can be used to limit the colors present in a Pixmap or other image, here with the goal of using 256 or fewer
 * colors in the image (for saving indexed-mode images). Can be used independently of classes like {@link AnimatedGif}
 * and {@link PNG8}, but it is meant to help with intelligently reducing the color count to fit under the maximum
 * palette size for those formats. You can use the {@link #exact(Color[])} method or its overloads to match a specific
 * palette exactly, or the {@link #analyze(Pixmap)} method or its overloads to analyze one or more Pixmaps and determine
 * which colors are most frequently-used. If using this class on its own, after calling exact(), analyze(), or a
 * constructor that uses them, you can use a specific dithering algorithm to reduce a Pixmap to the current palette.
 * Dithering algorithms that this supports:
 * <ul>
 *     <li>TOP TIER
 *     <ul>
 *     <li>{@link #reduceFloydSteinberg(Pixmap)} (Floyd-Steinberg is a very common error-diffusion dither; it's
 *     excellent for still images and large palette sizes, but not animations. It is great for preserving shape, but
 *     when a color isn't in the palette and it needs to try to match it, Floyd-Steinberg can leave artifacts.)</li>
 *     <li>{@link #reduceScatter(Pixmap)} (This is Floyd-Steinberg, as above, but with some of the problem artifacts
 *     that Floyd-Steinberg can produce perturbed by blue noise; it works well in still images and animations, but
 *     images with gradients will do better with Neue, below. Using blue noise to edit error somewhat-randomly would
 *     seem like it would introduce artifacts of its own, but blue noise patterns are very hard to recognize as
 *     artificial, since they show up mostly in organic forms. Scatter holds up very well to high ditherStrength, even
 *     to 2.0 or above, where most of the other dithers have problems, and looks similar to Floyd-Steinberg if using low
 *     ditherStrength.)</li>
 *     <li>{@link #reduceNeue(Pixmap)} (This is a variant on Scatter, above, that tends to be much smoother on gradients
 *     and has very little, if any, banding when using large palettes. A quirk this has is that it doesn't usually
 *     produce large flat areas of one color, instead preferring to dither softly between two similar colors if it can.
 *     This tends to look similar to Floyd-Steinberg and Scatter, because it is related to both of them, but also tends
 *     to have softer transitions between adjacent pixel colors, leading to less of a rough appearance from dithering.
 *     Neue is the default currently because it is the only dither that both handles gradients well and preserves color
 *     well. Blue Noise dither also handles gradients well, but doesn't always recognize color changes. Scatter handles
 *     color well, but can have some banding. Pattern dither usually handles gradients exceptionally well, but can have
 *     severe issues when it doesn't preserve lightness faithfully with small palettes. The list goes on. Neue can
 *     introduce error if the palette perfectly matches the image already; in that case, use Solid.)</li>
 *     <li>{@link #reduceBlueNoise(Pixmap)} (Uses a blue noise texture, which has almost no apparent patterns, to adjust
 *     the amount of color correction applied to each mismatched pixel; also uses an 8x8 Bayer matrix. This adds in
 *     a blue noise amount to each pixel, which is also what Neue does, but because this is a pure ordered dither, it
 *     doesn't take into consideration cumulative error built up over several poorly-matched pixels. It can often fill a
 *     whole region of color incorrectly if the palette isn't just right, though this is rare.)</li>
 *     <li>{@link #reduceKnoll(Pixmap)} (Thomas Knoll's Pattern Dithering, used more or less verbatim; this version has
 *     a heavy grid pattern that looks like an artifact. While the square grid here is a bit bad, it becomes very hard
 *     to see when the palette is large enough. This reduction is the slowest here, currently, and may noticeably delay
 *     processing on large images. Pattern dither handles complex gradients effortlessly, but struggles when the palette
 *     size is small -- in 4-color palettes, for instance, it doesn't match lightness correctly at all.)</li>
 *     </ul>
 *     </li>
 *     <li>OTHER TIER
 *     <ul>
 *     <li>{@link #reduceJimenez(Pixmap)} (This is a modified version of Gradient Interleaved Noise by Jorge Jimenez;
 *     it's a kind of ordered dither that introduces a subtle wave pattern to break up solid blocks. It does well on
 *     some animations and on smooth or rounded shapes, but still has issues with gradients. Also consider Neue for
 *     still images and Blue Noise for animations.)</li>
 *     <li>{@link #reduceSierraLite(Pixmap)} (Like Floyd-Steinberg, Sierra Lite is an error-diffusion dither, and it
 *     sometimes looks better than Floyd-Steinberg, but usually is similar or worse unless the palette is small. Sierra
 *     Lite tends to look comparable to Floyd-Steinberg if the Floyd-Steinberg dither was done with a lower
 *     ditherStrength.If Floyd-Steinberg has unexpected artifacts, you can try Sierra Lite, and it may avoid those
 *     issues. Using Scatter or Neue should be tried first, though.)</li>
 *     <li>{@link #reduceChaoticNoise(Pixmap)} (Uses blue noise and pseudo-random white noise, with a carefully chosen
 *     distribution, to disturb what would otherwise be flat bands. This does introduce chaotic or static-looking
 *     pixels, but with larger palettes they won't be far from the original. This works fine as a last resort when you
 *     can tolerate chaotic/fuzzy patches of poorly-defined shapes, but other dithers aren't doing well. It tends to
 *     still have flat bands when the palette is small, and it generally looks... rather ugly.)</li>
 *     <li>{@link #reduceKnollRoberts(Pixmap)} (This is a modified version of Thomas Knoll's Pattern Dithering; it skews
 *     a grid-based ordered dither and also handles lightness differently from the non-Knoll dithers. It preserves shape
 *     somewhat well, but is almost never 100% faithful to the original colors. This algorithm is rather slow; most of
 *     the other algorithms take comparable amounts of time to each other, but KnollRoberts and especially Knoll are
 *     sluggish.)</li>
 *     <li>{@link #reduceSolid(Pixmap)} (No dither! Solid colors! Mostly useful when you want to preserve blocky parts
 *     of a source image, or for some kinds of pixel/low-color art. If you have a palette that perfectly matches the
 *     image you are dithering, then you won't need dither, and this will be the best option.)</li>
 *     </ul>
 *     </li>
 * </ul>
 * <p>
 * Created by Tommy Ettinger on 6/23/2018.
 */
public class PaletteReducer {
    /**
     * A "geometric" palette generated by choosing quasi-random points that lay inside the Oklab gamut, and equalizing
     * the sizes of the "cells" around each color in Oklab space. Each "cell" is composed of the colors that are closest
     * to that particular cell's color, and having them be equal-size means there should be comparable representation
     * for any given hue/saturation/lightness combination.
     * <br>
     * This is currently the default.
     * <br>
     * While you can modify the individual items in this array, this is discouraged, because various constructors and
     * methods in this class use SNUGGLY with a pre-made distance mapping of its colors. This mapping would become
     * incorrect if any colors in this array changed.
     */
    public static final int[] SNUGGLY = {
            0x00000000, 0x000000FF, 0x111111FF, 0x20090DFF, 0x222222FF, 0x152C14FF, 0x333333FF, 0x444444FF,
            0x555555FF, 0x5C6365FF, 0x666666FF, 0x777777FF, 0x888888FF, 0x999999FF, 0xAAAAAAFF, 0xBBBBBBFF,
            0xC5BFBDFF, 0xCCCCCCFF, 0xDDDDDDFF, 0xEEEEEEFF, 0xFFFFFFFF, 0x9F1A1AFF, 0xF62923FF, 0xF57266FF,
            0xA9574AFF, 0xD14127FF, 0xF5927CFF, 0x923C26FF, 0xF7582BFF, 0xAF3E1DFF, 0xDD5926FF, 0xBD7152FF,
            0xB38A73FF, 0xBB5A22FF, 0xD68B5FFF, 0xD1A991FF, 0xF2782BFF, 0x76401BFF, 0x7E5B42FF, 0x583317FF,
            0xF1B384FF, 0xCF7627FF, 0xEECAA6FF, 0xF49432FF, 0x94591CFF, 0x3D2811FF, 0x947653FF, 0xA67220FF,
            0xD8AA61FF, 0xF4AF38FF, 0xD09329FF, 0xAD8C28FF, 0x67551DFF, 0xF0C63DFF, 0xB1A473FF, 0xD4B42EFF,
            0xB6A934FF, 0x78712EFF, 0xF0E241FF, 0xE2DB7FFF, 0xE9E5AEFF, 0x96945EFF, 0x878628FF, 0xD2D934FF,
            0x484C1FFF, 0xE8FA37FF, 0xC2CA82FF, 0xE9F884FF, 0xB8CF39FF, 0xA9C033FF, 0x90A62DFF, 0x576A24FF,
            0xC0F242FF, 0xE8FAC2FF, 0x709C30FF, 0x5C8326FF, 0x7DBC3EFF, 0x89E437FF, 0x8FFA37FF, 0x78D031FF,
            0x96BE7FFF, 0x9FE680FF, 0x1E4216FF, 0x4AB53EFF, 0xA6F99DFF, 0x225B1DFF, 0x257420FF, 0x359B2FFF,
            0x34C62CFF, 0x2CAC25FF, 0x3CF735FF, 0x689067FF, 0x36DF32FF, 0x238B24FF, 0x7BD77CFF, 0x77AC7AFF,
            0x51FA7BFF, 0xA7E5BEFF, 0x36D676FF, 0x3BEB8CFF, 0x8AD6AFFF, 0x36BD78FF, 0x2C8D5CFF, 0x31A56EFF,
            0x2A7756FF, 0x49F9BAFF, 0x296751FF, 0x3CCBA4FF, 0xACF8E4FF, 0x3ADCBFFF, 0x3C7E73FF, 0x21574EFF,
            0x79BBB3FF, 0x19433FFF, 0x58F9ECFF, 0x36B4AAFF, 0x3FEBE3FF, 0x49A19EFF, 0x299495FF, 0x64D8EAFF,
            0x33BDD8FF, 0x3ACDEAFF, 0x277A95FF, 0xA4DBEFFF, 0x2DA4D1FF, 0x26637EFF, 0x5A859CFF, 0x2A8BC4FF,
            0x39ACF2FF, 0x1E4B6CFF, 0x73B4EAFF, 0x6898C1FF, 0x93C0E8FF, 0x2D8EF0FF, 0x3077C7FF, 0x3F6BA2FF,
            0x23589DFF, 0x16355EFF, 0x2164C3FF, 0x2F70EFFF, 0x6791F0FF, 0x20418DFF, 0x2559EEFF, 0x909FCAFF,
            0x18254FFF, 0x2147C3FF, 0x677DC9FF, 0x1D319FFF, 0x233DEFFF, 0x5057CAFF, 0x686FEFFF, 0x14178BFF,
            0x9698F1FF, 0x101060FF, 0x0D0D31FF, 0x1717BBFF, 0x1E1BEBFF, 0x4A469DFF, 0x4C4971FF, 0x5042EDFF,
            0x6F6B98FF, 0x8983AEFF, 0x3C1DCBFF, 0xC5BBEEFF, 0x725FBFFF, 0x4E33B7FF, 0x704FEEFF, 0x311A7EFF,
            0x625490FF, 0x9577F0FF, 0x521CEDFF, 0xB9A3F0FF, 0x4215A4FF, 0x3E305AFF, 0x9F84D2FF, 0x6E3FBEFF,
            0x7522EDFF, 0x50307EFF, 0x9168CAFF, 0x2E1057FF, 0x9753F0FF, 0x6A18C5FF, 0x5E1797FF, 0x9624F0FF,
            0x8E46C4FF, 0xC37CF0FF, 0x4D126CFF, 0x71378FFF, 0xBD58F1FF, 0x921CCAFF, 0xB523ECFF, 0x8019A5FF,
            0xB347C9FF, 0x85528EFF, 0x2B0E30FF, 0xB76AC4FF, 0xD825EEFF, 0xAC1CB7FF, 0x996B9CFF, 0xCD8BD0FF,
            0x701674FF, 0xE758EDFF, 0x963798FF, 0xEEA4ECFF, 0xCC1FC7FF, 0xEF89ECFF, 0xF52AEEFF, 0xA550A2FF,
            0xD74BCBFF, 0xEEC0EAFF, 0xF26FE5FF, 0x921684FF, 0xC9A2C3FF, 0xF2D5EDFF, 0x6E3A65FF, 0x46133CFF,
            0xF221C2FF, 0xB92095FF, 0xB588A9FF, 0xD821A0FF, 0xD470B4FF, 0x621449FF, 0xCA50A2FF, 0xF450B8FF,
            0xA7196DFF, 0x7F1753FF, 0x913668FF, 0xF62691FF, 0xC91871FF, 0xBE3E7AFF, 0xA45275FF, 0xE42173FF,
            0x582D3FFF, 0xF28FB8FF, 0xDB5286FF, 0xF473A3FF, 0x7E4D5DFF, 0xB31848FF, 0xB27185FF, 0x911A3EFF,
            0xF75480FF, 0xF62459FF, 0xD5214DFF, 0xF0ACBBFF, 0x8F656DFF, 0xAC3C4FFF, 0xC85461FF, 0xD58D95FF,
            0x73353AFF, 0x5E1318FF, 0x3E1013FF, 0xD77076FF, 0xEE4B52FF, 0xE01A21FF, 0xC11B1FFF, 0x7C181AFF,
    };
    /**
     * DawnBringer's 256-color Aurora palette, modified slightly to fit one transparent color by removing one gray.
     * Aurora is available in <a href="https://pixeljoint.com/forum/forum_posts.asp?TID=26080">this set of tools</a>
     * for a pixel art editor, but it is usable for lots of high-color purposes.
     * <br>
     * These colors all have names, which can be seen <a href="https://i.imgur.com/2oChRYC.png">previewed here</a>. The
     * linked image preview also shows a nearby lighter color and two darker colors on the same sphere as the main
     * color; the second-lightest color is what has the listed name. The names here are used by a few other libraries,
     * such as <a href="https://github.com/tommyettinger/colorful-gdx">colorful-gdx</a>, but otherwise don't matter.
     * <br>
     * This was the default for many releases, and had replaced another palette, Haltonic, that wasn't hand-chosen and
     * was much more "randomized." Aurora was the first palette used as a default here, and it was replaced because the
     * color metric at the time made it look bad. Aurora still looks good a lot of the time, but it has trouble with
     * shades of yellow and with light green, at least. It was replaced with the default Snuggly palette in 0.4.4 .
     * <br>
     * While you can modify the individual items in this array, this is discouraged, because various constructors and
     * methods in this class use AURORA with a pre-made distance mapping of its colors. This mapping would become
     * incorrect if any colors in this array changed.
     */
    public static final int[] AURORA = {
            0x00000000, 0x010101FF, 0x131313FF, 0x252525FF, 0x373737FF, 0x494949FF, 0x5B5B5BFF, 0x6E6E6EFF,
            0x808080FF, 0x929292FF, 0xA4A4A4FF, 0xB6B6B6FF, 0xC9C9C9FF, 0xDBDBDBFF, 0xEDEDEDFF, 0xFFFFFFFF,
            0x007F7FFF, 0x3FBFBFFF, 0x00FFFFFF, 0xBFFFFFFF, 0x8181FFFF, 0x0000FFFF, 0x3F3FBFFF, 0x00007FFF,
            0x0F0F50FF, 0x7F007FFF, 0xBF3FBFFF, 0xF500F5FF, 0xFD81FFFF, 0xFFC0CBFF, 0xFF8181FF, 0xFF0000FF,
            0xBF3F3FFF, 0x7F0000FF, 0x551414FF, 0x7F3F00FF, 0xBF7F3FFF, 0xFF7F00FF, 0xFFBF81FF, 0xFFFFBFFF,
            0xFFFF00FF, 0xBFBF3FFF, 0x7F7F00FF, 0x007F00FF, 0x3FBF3FFF, 0x00FF00FF, 0xAFFFAFFF, 0xBCAFC0FF,
            0xCBAA89FF, 0xA6A090FF, 0x7E9494FF, 0x6E8287FF, 0x7E6E60FF, 0xA0695FFF, 0xC07872FF, 0xD08A74FF,
            0xE19B7DFF, 0xEBAA8CFF, 0xF5B99BFF, 0xF6C8AFFF, 0xF5E1D2FF, 0x573B3BFF, 0x73413CFF, 0x8E5555FF,
            0xAB7373FF, 0xC78F8FFF, 0xE3ABABFF, 0xF8D2DAFF, 0xE3C7ABFF, 0xC49E73FF, 0x8F7357FF, 0x73573BFF,
            0x3B2D1FFF, 0x414123FF, 0x73733BFF, 0x8F8F57FF, 0xA2A255FF, 0xB5B572FF, 0xC7C78FFF, 0xDADAABFF,
            0xEDEDC7FF, 0xC7E3ABFF, 0xABC78FFF, 0x8EBE55FF, 0x738F57FF, 0x587D3EFF, 0x465032FF, 0x191E0FFF,
            0x235037FF, 0x3B573BFF, 0x506450FF, 0x3B7349FF, 0x578F57FF, 0x73AB73FF, 0x64C082FF, 0x8FC78FFF,
            0xA2D8A2FF, 0xE1F8FAFF, 0xB4EECAFF, 0xABE3C5FF, 0x87B48EFF, 0x507D5FFF, 0x0F6946FF, 0x1E2D23FF,
            0x234146FF, 0x3B7373FF, 0x64ABABFF, 0x8FC7C7FF, 0xABE3E3FF, 0xC7F1F1FF, 0xBED2F0FF, 0xABC7E3FF,
            0xA8B9DCFF, 0x8FABC7FF, 0x578FC7FF, 0x57738FFF, 0x3B5773FF, 0x0F192DFF, 0x1F1F3BFF, 0x3B3B57FF,
            0x494973FF, 0x57578FFF, 0x736EAAFF, 0x7676CAFF, 0x8F8FC7FF, 0xABABE3FF, 0xD0DAF8FF, 0xE3E3FFFF,
            0xAB8FC7FF, 0x8F57C7FF, 0x73578FFF, 0x573B73FF, 0x3C233CFF, 0x463246FF, 0x724072FF, 0x8F578FFF,
            0xAB57ABFF, 0xAB73ABFF, 0xEBACE1FF, 0xFFDCF5FF, 0xE3C7E3FF, 0xE1B9D2FF, 0xD7A0BEFF, 0xC78FB9FF,
            0xC87DA0FF, 0xC35A91FF, 0x4B2837FF, 0x321623FF, 0x280A1EFF, 0x401811FF, 0x621800FF, 0xA5140AFF,
            0xDA2010FF, 0xD5524AFF, 0xFF3C0AFF, 0xF55A32FF, 0xFF6262FF, 0xF6BD31FF, 0xFFA53CFF, 0xD79B0FFF,
            0xDA6E0AFF, 0xB45A00FF, 0xA04B05FF, 0x5F3214FF, 0x53500AFF, 0x626200FF, 0x8C805AFF, 0xAC9400FF,
            0xB1B10AFF, 0xE6D55AFF, 0xFFD510FF, 0xFFEA4AFF, 0xC8FF41FF, 0x9BF046FF, 0x96DC19FF, 0x73C805FF,
            0x6AA805FF, 0x3C6E14FF, 0x283405FF, 0x204608FF, 0x0C5C0CFF, 0x149605FF, 0x0AD70AFF, 0x14E60AFF,
            0x7DFF73FF, 0x4BF05AFF, 0x00C514FF, 0x05B450FF, 0x1C8C4EFF, 0x123832FF, 0x129880FF, 0x06C491FF,
            0x00DE6AFF, 0x2DEBA8FF, 0x3CFEA5FF, 0x6AFFCDFF, 0x91EBFFFF, 0x55E6FFFF, 0x7DD7F0FF, 0x08DED5FF,
            0x109CDEFF, 0x055A5CFF, 0x162C52FF, 0x0F377DFF, 0x004A9CFF, 0x326496FF, 0x0052F6FF, 0x186ABDFF,
            0x2378DCFF, 0x699DC3FF, 0x4AA4FFFF, 0x90B0FFFF, 0x5AC5FFFF, 0xBEB9FAFF, 0x00BFFFFF, 0x007FFFFF,
            0x4B7DC8FF, 0x786EF0FF, 0x4A5AFFFF, 0x6241F6FF, 0x3C3CF5FF, 0x101CDAFF, 0x0010BDFF, 0x231094FF,
            0x0C2148FF, 0x5010B0FF, 0x6010D0FF, 0x8732D2FF, 0x9C41FFFF, 0x7F00FFFF, 0xBD62FFFF, 0xB991FFFF,
            0xD7A5FFFF, 0xD7C3FAFF, 0xF8C6FCFF, 0xE673FFFF, 0xFF52FFFF, 0xDA20E0FF, 0xBD29FFFF, 0xBD10C5FF,
            0x8C14BEFF, 0x5A187BFF, 0x641464FF, 0x410062FF, 0x320A46FF, 0x551937FF, 0xA01982FF, 0xC80078FF,
            0xFF50BFFF, 0xFF6AC5FF, 0xFAA0B9FF, 0xFC3A8CFF, 0xE61E78FF, 0xBD1039FF, 0x98344DFF, 0x911437FF,
    };

    /**
     * An expanded palette with 1024 colors instead of {@link #SNUGGLY}'s 256. This includes fully-transparent black
     * as its first item, but has no other transparent or partially transparent colors. This is meant to be used by
     * {@link #analyzeReductive(Pixmap, double, int)} as a basis that can have colors removed if they don't match often
     * in a source image.
     */
    public static final int[] BIG_PALETTE = new int[]{
//            0x00000000, 0x15111BFF, 0x271C1DFF, 0x212230FF, 0x372B26FF, 0x5B464EFF, 0x4F4F47FF, 0x435257FF,
//            0x4F5155FF, 0x5C5055FF, 0x654F50FF, 0x525754FF, 0x465F5FFF, 0x6F565BFF, 0x4A6469FF, 0x5B6276FF,
//            0x686260FF, 0x55686AFF, 0x616C70FF, 0x786371FF, 0x607577FF, 0x6C7B6FFF, 0x72798CFF, 0x827588FF,
//            0x6E8174FF, 0x8F7678FF, 0x887D7EFF, 0x89807DFF, 0x8A8475FF, 0x848A75FF, 0x9A8887FF, 0x7B948EFF,
//            0x918F8BFF, 0xA48888FF, 0xA48886FF, 0x979284FF, 0x8C949FFF, 0x9A9CA3FF, 0x9C9F9BFF, 0x9FA1A5FF,
//            0xA99FA1FF, 0xA19FB9FF, 0xB99C93FF, 0x92AAACFF, 0x94AAB7FF, 0x99A9BCFF, 0xB4A4ABFF, 0xABADBEFF,
//            0xBCA9B7FF, 0xBAACAFFF, 0xADBCA6FF, 0xC6B5A5FF, 0xB6BBBAFF, 0xD2BAB4FF, 0xC5BFD8FF, 0xC1CAB5FF,
//            0xB4CFC5FF, 0xC2E2E8FF, 0xE5DCEEFF, 0xECE5E0FF, 0xFFEDD4FF, 0xF6F2D6FF, 0xFD3684FF, 0xEA6791FF,
//            0xBF8494FF, 0xA27E87FF, 0x9B0C4BFF, 0xE84E81FF, 0xF02E78FF, 0xC96682FF, 0xE989A2FF, 0xD5376FFF,
//            0xC22B62FF, 0x532B36FF, 0xEDA6B7FF, 0x691433FF, 0xC4115BFF, 0xBE5B76FF, 0x925262FF, 0x72444FFF,
//            0x4A1D29FF, 0xC75A76FF, 0xEF6B8CFF, 0xFB5D88FF, 0xE74876FF, 0xAA3155FF, 0xF3266CFF, 0x6A2B3BFF,
//            0xFE91A8FF, 0xA36B76FF, 0xA9485FFF, 0xF7CAD1FF, 0xAC5567FF, 0xC1838DFF, 0xFF7B94FF, 0xEB8193FF,
//            0xD5939DFF, 0xE56B82FF, 0x7A5056FF, 0xCC8D95FF, 0xDF8E99FF, 0xFB7189FF, 0xBA2449FF, 0xA80B3CFF,
//            0x891633FF, 0x380D16FF, 0xCF737EFF, 0x782031FF, 0xAD898CFF, 0xDD9BA1FF, 0xE89FA5FF, 0xC38E92FF,
//            0x563437FF, 0xF26476FF, 0x86172FFF, 0x913643FF, 0xCB878CFF, 0xB44C58FF, 0xA65C62FF, 0xCE6B74FF,
//            0xA85F65FF, 0xFB6E7DFF, 0xB05059FF, 0xCB3C50FF, 0xBF8184FF, 0xB83748FF, 0xDF5A67FF, 0xE66671FF,
//            0x884146FF, 0x9B2637FF, 0x9D5156FF, 0xE68C90FF, 0xEA3A52FF, 0x9C6667FF, 0x93323BFF, 0xE04A57FF,
//            0xDC4452FF, 0xE74E5AFF, 0xC91738FF, 0xDC3F4EFF, 0xA77E7EFF, 0x7B1C25FF, 0xD68988FF, 0xA03C3EFF,
//            0x6B4B4AFF, 0xA95D5BFF, 0xDF6363FF, 0xB06D6AFF, 0xE68682FF, 0xA1706DFF, 0xCA6662FF, 0x71423FFF,
//            0xBF6E6AFF, 0xEBC2BFFF, 0x743230FF, 0xE03D40FF, 0xCF817BFF, 0xBD0E22FF, 0x714340FF, 0xDB3538FF,
//            0x7E3734FF, 0xE79B94FF, 0x93635EFF, 0xA26963FF, 0x835A56FF, 0xBC322FFF, 0x89615CFF, 0xC6524BFF,
//            0xC83C37FF, 0xF8B7AFFF, 0xE6493FFF, 0xEB7E70FF, 0x935B53FF, 0xF41C0EFF, 0xA43D2FFF, 0xFF725DFF,
//            0xCA9186FF, 0xC34B38FF, 0xEF6B55FF, 0xF87961FF, 0xEB7D65FF, 0xDCA699FF, 0xC37C6BFF, 0xD26349FF,
//            0xE65432FF, 0xD4573AFF, 0xDD5332FF, 0xD6765DFF, 0xEBB5A6FF, 0xD14924FF, 0xDF6C4CFF, 0xD88269FF,
//            0x562F24FF, 0xF95322FF, 0xD37C62FF, 0x8D3316FF, 0xE5480BFF, 0xA8634CFF, 0xD2A291FF, 0xB65838FF,
//            0xD1795AFF, 0x935540FF, 0xEF500BFF, 0x7B3013FF, 0x88503CFF, 0xB3715AFF, 0xDF7F5DFF, 0xAD502EFF,
//            0xFEB89FFF, 0xE09273FF, 0xDD997EFF, 0x943F1BFF, 0xBC470BFF, 0xC3795AFF, 0xBF7352FF, 0xAB6040FF,
//            0xAB755EFF, 0xD6784FFF, 0xE17A4CFF, 0x8D4C2FFF, 0x6C3B23FF, 0xE2AD95FF, 0xAC9082FF, 0x95481DFF,
//            0xE77D44FF, 0xE48C5FFF, 0x452A1CFF, 0xA36A4CFF, 0xF87C34FF, 0xF8CBB2FF, 0xE0956DFF, 0xF9843FFF,
//            0xAB5A28FF, 0x954509FF, 0xDFA07BFF, 0xAE6030FF, 0xED7721FF, 0x925C39FF, 0xE58341FF, 0xE67F37FF,
//            0xBA5A05FF, 0xEF7D27FF, 0xB58A6EFF, 0xCA8250FF, 0x9A765EFF, 0xEFCBB3FF, 0xB38464FF, 0xF7BB92FF,
//            0x907969FF, 0xB0815FFF, 0xB09078FF, 0xAB8569FF, 0xB98862FF, 0x9F5A1DFF, 0xFDB986FF, 0x865328FF,
//            0xC58654FF, 0xFBA45FFF, 0xE9B58CFF, 0x8E6646FF, 0xBB7C47FF, 0x915003FF, 0xA88567FF, 0xCA7E33FF,
//            0xBF7422FF, 0xCF904FFF, 0x8F5B1FFF, 0x5C4936FF, 0xB68D61FF, 0xFF9C19FF, 0xF69814FF, 0xE89323FF,
//            0xEB9930FF, 0xAE7834FF, 0x513714FF, 0xB8823BFF, 0xD7B993FF, 0xD88C12FF, 0xC29B65FF, 0xB37A1EFF,
//            0xC3A67DFF, 0xC5A26EFF, 0xB38643FF, 0xDFA957FF, 0xA69274FF, 0xFBDBA9FF, 0x7E5815FF, 0xB98939FF,
//            0xE1AD56FF, 0x9D7A3EFF, 0xB69153FF, 0xBF9445FF, 0x60491BFF, 0xEBAD13FF, 0xEFB632FF, 0x95752DFF,
//            0xF7DFAAFF, 0x887445FF, 0x84661EFF, 0x6E6550FF, 0xE0AF2DFF, 0xEFBA1FFF, 0xE2D3ACFF, 0x856C28FF,
//            0x8D700BFF, 0xE8CD7CFF, 0xAC9753FF, 0x7B6515FF, 0x827239FF, 0x4F4213FF, 0xDAB945FF, 0xEDCD5FFF,
//            0x856F15FF, 0x504725FF, 0xC8C1A0FF, 0xF8D957FF, 0x978A4FFF, 0x85742AFF, 0xC3B984FF, 0xDAC13BFF,
//            0xB8AD70FF, 0xC6AF2AFF, 0x726D4AFF, 0xA1953FFF, 0x969373FF, 0xF6F2CEFF, 0x878049FF, 0x8C8756FF,
//            0xC9BD44FF, 0xCBC57FFF, 0x666444FF, 0x827F56FF, 0x48451FFF, 0xBCB65EFF, 0x3D3C16FF, 0xDADA9AFF,
//            0x4A4808FF, 0xB4B241FF, 0xA6A41AFF, 0xBBBD52FF, 0x9C9E3DFF, 0x6B6E26FF, 0x888C4FFF, 0xC8D072FF,
//            0x474B09FF, 0xE1E9A2FF, 0x979F1AFF, 0x5D5F49FF, 0x676E36FF, 0xB0BC80FF, 0x121404FF, 0x9BAE57FF,
//            0x6A7B13FF, 0xACB887FF, 0x8B985EFF, 0xDDF596FF, 0x4B5A11FF, 0x97A671FF, 0x688003FF, 0xBBD18AFF,
//            0x99B15EFF, 0x89A73BFF, 0xBBD976FF, 0x6A851EFF, 0x97B35FFF, 0x78904BFF, 0x718E41FF, 0x4E6820FF,
//            0xC7DBADFF, 0x9FC071FF, 0x849173FF, 0x93C45FFF, 0xAFE873FF, 0x80B743FF, 0x91DE2EFF, 0x83A067FF,
//            0x4D7718FF, 0x9EFD14FF, 0x95B974FF, 0x536C40FF, 0x8AC856FF, 0x8FAC7BFF, 0xA1D17DFF, 0x70B536FF,
//            0x81E92BFF, 0x69BB33FF, 0x557A41FF, 0x99B988FF, 0xB2D0A3FF, 0x71B54CFF, 0x355525FF, 0x7FC75CFF,
//            0x73BB50FF, 0xACEB90FF, 0x426630FF, 0x6BDE24FF, 0x87A47CFF, 0x80BA6CFF, 0x4A783BFF, 0x5B814FFF,
//            0x51A437FF, 0x63BF48FF, 0x317F1AFF, 0x70E253FF, 0x47DD0CFF, 0x89C67BFF, 0x8FFB7BFF, 0x41633BFF,
//            0x597A54FF, 0x5AC24CFF, 0x4DB23FFF, 0x90CF87FF, 0x49A640FF, 0x7EA579FF, 0x6D9169FF, 0x6FC66AFF,
//            0x2E9E2BFF, 0x56C252FF, 0x214020FF, 0x78E175FF, 0x90D98EFF, 0x18691DFF, 0x38AF3DFF, 0x3B673BFF,
//            0x259B2FFF, 0x3E8340FF, 0x9AF09BFF, 0x72A372FF, 0x587758FF, 0xA5E5A6FF, 0x519859FF, 0x9AB79CFF,
//            0x1F8D38FF, 0x50C865FF, 0x60E177FF, 0x439451FF, 0x3C8349FF, 0x51A55FFF, 0x62966AFF, 0x9EC7A4FF,
//            0x85E298FF, 0x6BEB8BFF, 0x64A973FF, 0x66B979FF, 0x112D19FF, 0x76A17FFF, 0xD4FFDDFF, 0x37844FFF,
//            0x509363FF, 0x41E67DFF, 0xADECBDFF, 0x0FD068FF, 0x26AE5DFF, 0x6FB282FF, 0x536A59FF, 0x98C9A6FF,
//            0x6EA980FF, 0x62A377FF, 0x7A9C85FF, 0x7FD99EFF, 0x45644FFF, 0x31DE86FF, 0x5B7E67FF, 0x86BB9AFF,
//            0x63A27CFF, 0x7FD2A1FF, 0x91BBA1FF, 0x15452DFF, 0x8BE4B1FF, 0x6C8F7AFF, 0x5EAC85FF, 0x70AC8CFF,
//            0x4C7A62FF, 0x64EBABFF, 0x67C99AFF, 0x9BCFB4FF, 0x71AF90FF, 0x7DD4ABFF, 0x5CB78EFF, 0x90AE9FFF,
//            0x1AB17BFF, 0x61E1AAFF, 0x5EAF8BFF, 0x53D7A0FF, 0x4FA585FF, 0x5D8C7AFF, 0x49F0BDFF, 0x497363FF,
//            0x427765FF, 0x15B78EFF, 0x7EA799FF, 0x317F6BFF, 0x42D2B1FF, 0x729389FF, 0x7BB0A2FF, 0x77AC9FFF,
//            0x76A79BFF, 0x0E6C5CFF, 0x25AD95FF, 0x38BDA6FF, 0x7FBDB0FF, 0x8BAEA7FF, 0x478479FF, 0xA9CFC7FF,
//            0x55B5A5FF, 0x8ABAB2FF, 0x35FDE8FF, 0x50928AFF, 0x0CCFBFFF, 0x3B615CFF, 0x3CD9CAFF, 0x54C1B6FF,
//            0x6A928FFF, 0x5FE2DAFF, 0x558A86FF, 0xA0D7D4FF, 0x33D7D1FF, 0x4E7C79FF, 0x0D7976FF, 0x458F8CFF,
//            0x139391FF, 0x7ED3D3FF, 0x0B7778FF, 0x609A9BFF, 0x48BDC0FF, 0x177679FF, 0x68B2B5FF, 0x439EA2FF,
//            0x75C9CFFF, 0x69BAC1FF, 0x5C8287FF, 0x186B74FF, 0x3F787FFF, 0x67ECFFFF, 0x35757EFF, 0x56C4D4FF,
//            0x74B9C4FF, 0x153338FF, 0x43DFFAFF, 0x93C8D3FF, 0x18CDEBFF, 0x38808EFF, 0x47808DFF, 0x5D9BA9FF,
//            0x85A9B2FF, 0x4A8896FF, 0x396A76FF, 0x88BDCBFF, 0x48D9FCFF, 0x65B7CDFF, 0x37D6FFFF, 0x779EAAFF,
//            0x6695A4FF, 0x6DCBEAFF, 0x8CE0FEFF, 0x22C9F9FF, 0x2FA6CBFF, 0x59889AFF, 0x034557FF, 0x42A8CBFF,
//            0x84DBFCFF, 0x2F596AFF, 0x75AEC4FF, 0x55A5C6FF, 0x2FB9EDFF, 0xB7E4F8FF, 0x4390B1FF, 0x1DABE0FF,
//            0x548196FF, 0x63ACCFFF, 0x64B3DAFF, 0x89C2E1FF, 0x6AB4DBFF, 0x9CC9E2FF, 0x30B9FBFF, 0x63A0C1FF,
//            0x4089B3FF, 0x4DAEE5FF, 0x599CC5FF, 0x0A6B9BFF, 0x348BBFFF, 0x365F79FF, 0x2D79A8FF, 0x4EB2F1FF,
//            0x4D92BEFF, 0x66C2FFFF, 0x28536FFF, 0x4B9ACFFF, 0x84A7C1FF, 0x71A4C8FF, 0x478DBEFF, 0x52ACEBFF,
//            0x2793D9FF, 0x86A2B7FF, 0x94CBF4FF, 0x105D8DFF, 0x2C90D5FF, 0x3178ABFF, 0x5DB2F5FF, 0x154F7AFF,
//            0x5894C8FF, 0x649ECFFF, 0x559BD7FF, 0x5492C8FF, 0x143E62FF, 0x4E92D0FF, 0x4C6E8FFF, 0x3B8AD3FF,
//            0x3B4E62FF, 0x70ABE7FF, 0x67ACF5FF, 0x4097F9FF, 0x8095ADFF, 0x4C77A9FF, 0x5696E2FF, 0x223245FF,
//            0x7EB2F1FF, 0x92B3DBFF, 0x4778B4FF, 0x6FA2E1FF, 0xAFCBEFFF, 0x4F81C1FF, 0x175BADFF, 0x5A9BF5FF,
//            0x45628AFF, 0x576C89FF, 0x4C84D7FF, 0x3078E8FF, 0x2C3F5DFF, 0x354F79FF, 0x5A84CCFF, 0x4483FAFF,
//            0x4881ECFF, 0x687CA0FF, 0x5A7AB7FF, 0x1540A5FF, 0x0436B2FF, 0x6591EFFF, 0x8CB0FBFF, 0x5F80C7FF,
//            0x4867ACFF, 0x90B2FAFF, 0x3B68E2FF, 0x6286E1FF, 0xC9DAFFFF, 0x496BDBFF, 0x6F8EE6FF, 0xABBBE4FF,
//            0x7690DBFF, 0x7895ECFF, 0x667BBAFF, 0x6887F2FF, 0x91A1D1FF, 0x4863ECFF, 0x273588FF, 0x2A3354FF,
//            0x5368BFFF, 0x919EC9FF, 0x4254D2FF, 0x4F63D9FF, 0x5165EEFF, 0x687FF7FF, 0x161163FF, 0x3C46BBFF,
//            0x8996C8FF, 0x2D3294FF, 0x4B58CDFF, 0x4C56E1FF, 0x5F6DBCFF, 0x343B90FF, 0x6672BFFF, 0x352BBBFF,
//            0x2C21A1FF, 0x6D79CCFF, 0x727BB4FF, 0x2C199EFF, 0x4649B8FF, 0x3F3DB8FF, 0x767EFDFF, 0x3D4186FF,
//            0x989FC7FF, 0x371EB9FF, 0x8F96C6FF, 0x8A92F7FF, 0x7A81F5FF, 0x878ED3FF, 0x5546F6FF, 0x5C55E9FF,
//            0x7171E1FF, 0x5949E9FF, 0x656784FF, 0x6861E7FF, 0x636498FF, 0x605DC0FF, 0xC0C2E3FF, 0x7273AFFF,
//            0x7C77F1FF, 0x7875CDFF, 0x535378FF, 0x9490F1FF, 0x807EBAFF, 0x6158B1FF, 0x8281A4FF, 0x65647DFF,
//            0x5F55AEFF, 0x8573F9FF, 0x641FFFFF, 0x8782BDFF, 0x534A8BFF, 0x9382F7FF, 0x5C5295FF, 0x6E51D9FF,
//            0x49289FFF, 0x6F6A93FF, 0x33027BFF, 0xACA5E0FF, 0x968BD4FF, 0x8476C2FF, 0x8F74F0FF, 0x5F5C72FF,
//            0x2D293BFF, 0x7659CEFF, 0x9F82FCFF, 0x726995FF, 0x2A0D59FF, 0x8B7AC5FF, 0x7E54E0FF, 0x793AEBFF,
//            0x81799FFF, 0x7A51D6FF, 0xB3A1EDFF, 0x8267C9FF, 0x7653C7FF, 0xA289E9FF, 0x573995FF, 0x6B5D93FF,
//            0x5B488AFF, 0x9560FAFF, 0x8174A3FF, 0xB5A7DDFF, 0x946AEAFF, 0x918AA6FF, 0x221639FF, 0x6B49ACFF,
//            0x6938B9FF, 0xA679FFFF, 0x9E69FFFF, 0x48287BFF, 0xA189D8FF, 0x7731D6FF, 0xB9AADDFF, 0x624A8FFF,
//            0x312843FF, 0x4A2F75FF, 0x8840EBFF, 0x60379EFF, 0x67548AFF, 0x4F118EFF, 0x726689FF, 0x9171C7FF,
//            0x9883BEFF, 0x9B59F3FF, 0x866FADFF, 0x8A42E0FF, 0x9067CCFF, 0x998EADFF, 0xAD91D6FF, 0x634589FF,
//            0x8643D0FF, 0xB699E0FF, 0xA878E5FF, 0x8F76B2FF, 0xAC9FBFFF, 0x8F5DCAFF, 0xC09AEFFF, 0x8C55C9FF,
//            0x6A2DA5FF, 0xA186C2FF, 0x9452D7FF, 0x9A76BFFF, 0xAE66F0FF, 0xB189D9FF, 0x592F7CFF, 0x630E9AFF,
//            0x651C98FF, 0xA72EFCFF, 0x7620B2FF, 0x8A51BAFF, 0x8B58B6FF, 0xBC72F7FF, 0x9E38E4FF, 0x372743FF,
//            0xA42EE8FF, 0x7C519AFF, 0x6F1F9CFF, 0xAA6AD4FF, 0x533A63FF, 0x5D3276FF, 0x3B204BFF, 0x956EADFF,
//            0x46384FFF, 0xB261E1FF, 0xBA8ED4FF, 0xA873C6FF, 0x703F8CFF, 0x8E4DB2FF, 0xA065C1FF, 0xC084E0FF,
//            0x8447A2FF, 0x6A447DFF, 0xBA65E2FF, 0xAF5ED5FF, 0x866A93FF, 0xAE7AC6FF, 0xB0A0B7FF, 0xCF6EF4FF,
//            0xB53FE0FF, 0x76418AFF, 0xAE40D5FF, 0xC153E9FF, 0xAE53D0FF, 0xB428E2FF, 0x8123A0FF, 0x593A63FF,
//            0xB878CCFF, 0x925EA2FF, 0xD78DEDFF, 0xB07FBEFF, 0xC663E3FF, 0x9A59ADFF, 0xA963BDFF, 0xD369F0FF,
//            0x9633AFFF, 0xA553BAFF, 0xB75ECDFF, 0x6A4174FF, 0xB588C0FF, 0xC7A2CFFF, 0x9C2BB4FF, 0xC5AACAFF,
//            0x9027A6FF, 0x9D40B0FF, 0xC436DFFF, 0x9949A8FF, 0x9651A3FF, 0xD281E0FF, 0xDA3BF6FF, 0x95639DFF,
//            0x793F83FF, 0x892998FF, 0xA15EACFF, 0xC8A0CDFF, 0xD51CEEFF, 0x644867FF, 0x946F97FF, 0xA972AEFF,
//            0xE625FBFF, 0x9E2EA6FF, 0xC5A2C6FF, 0x721778FF, 0xA951ACFF, 0x912794FF, 0x8A6289FF, 0x6F3170FF,
//            0xC291C0FF, 0xDF3AE2FF, 0xE15DE2FF, 0x974495FF, 0xCE83CAFF, 0x7D257CFF, 0xFBCBF8FF, 0xBF5ABCFF,
//            0x922E91FF, 0xA351A0FF, 0xBF40BCFF, 0xFB61F6FF, 0xD82DD5FF, 0xDD92D8FF, 0xE05ADAFF, 0xB276ADFF,
//            0xC76DC1FF, 0xAD5CA7FF, 0x9D6098FF, 0x9D2B98FF, 0xC346BCFF, 0xB95CB1FF, 0x85257FFF, 0x712C6CFF,
//            0xA82AA0FF, 0xEA23DBFF, 0xA85F9EFF, 0xEA95DEFF, 0xC43FB7FF, 0xB159A5FF, 0x78226EFF, 0x7A0470FF,
//            0xCC86C0FF, 0x7A0E70FF, 0xC822B6FF, 0xE82AD1FF, 0x755A6FFF, 0xB246A2FF, 0xEA7BD7FF, 0x5B2F53FF,
//            0xBA65ABFF, 0xA34193FF, 0xAF6EA2FF, 0x930C82FF, 0x704C68FF, 0xC619AFFF, 0xCB64B7FF, 0x8B347CFF,
//            0x621256FF, 0x87587DFF, 0xBB7DADFF, 0xC2A2B9FF, 0xFAA1E5FF, 0x9B3888FF, 0xF59CDFFF, 0xC75CAFFF,
//            0x955586FF, 0xD681C0FF, 0xC985B6FF, 0xC539A6FF, 0xC725A5FF, 0xF44BCDFF, 0x974080FF, 0xD3AFC8FF,
//            0xAD5D96FF, 0xDF66BEFF, 0xC82FA3FF, 0xC849A5FF, 0x90567EFF, 0xE15DBAFF, 0xFA51CAFF, 0xD983BBFF,
//            0x861A68FF, 0xEC2AB6FF, 0xB982A4FF, 0xA57B95FF, 0x553549FF, 0xF632BDFF, 0xBB3B90FF, 0xE971BCFF,
//            0xCB2C98FF, 0xB46093FF, 0xBA2E8BFF, 0xF158B9FF, 0xA97191FF, 0xA77090FF, 0x953170FF, 0x633750FF,
//            0x6F3D59FF, 0xC03D8DFF, 0x58103FFF, 0xC8028CFF, 0x411630FF, 0xEB4CACFF, 0x8B697BFF, 0xAA7C94FF,
//            0xD959A3FF, 0xBD3888FF, 0xD073A6FF, 0x79616DFF, 0x9D567CFF, 0x612E4AFF, 0xB8578BFF, 0xFDA7D3FF,
//            0xCF86ABFF, 0xA75681FF, 0xA43574FF, 0xF9A5CFFF, 0xC69AAFFF, 0xB93B81FF, 0x937482FF, 0x7F335BFF,
//            0xF686BEFF, 0xF15FABFF, 0x6F274DFF, 0xCC3A8AFF, 0xA74577FF, 0xD1478FFF, 0xA64F79FF, 0x876272FF,
//            0xAD8596FF, 0x86536AFF, 0xDD3A8FFF, 0x6C4C5AFF, 0xEC3E97FF, 0x8C2E5CFF, 0xC17093FF, 0xCD267EFF,
//            0xC17D99FF, 0xC095A5FF, 0xA93F71FF, 0xAB577BFF, 0xB7909FFF, 0xA24D72FF, 0xD5A2B5FF, 0xB42A6DFF,
//            0xE32887FF, 0xF661A2FF, 0xF01489FF, 0xCE4F86FF, 0x8B4863FF, 0xB03D6FFF, 0xB25579FF, 0xE3A6BBFF,
//            0xE6689AFF, 0x9D7683FF, 0xD3417DFF, 0x954060FF, 0xCC879EFF, 0x981953FF, 0xC37F95FF, 0xF899B8FF,
//            0x89495FFF, 0xC48699FF, 0x401426FF, 0xD9397AFF, 0xAF476DFF, 0xC34675FF, 0xC94276FF, 0xB3436CFF,

            0x00000000, 0x000000FF, 0xFFFFFFFF, 0x080808FF, 0x101010FF, 0x181818FF, 0x202020FF, 0x292929FF,
            0x313131FF, 0x393939FF, 0x414141FF, 0x4A4A4AFF, 0x525252FF, 0x5A5A5AFF, 0x626262FF, 0x6A6A6AFF,
            0x737373FF, 0x7B7B7BFF, 0x838383FF, 0x8B8B8BFF, 0x949494FF, 0x9C9C9CFF, 0xA4A4A4FF, 0xACACACFF,
            0xB4B4B4FF, 0xBDBDBDFF, 0xC5C5C5FF, 0xCDCDCDFF, 0xD5D5D5FF, 0xDEDEDEFF, 0xE6E6E6FF, 0xEEEEEEFF,
            0xF6F6F6FF, 0xF225B9FF, 0xE3DB38FF, 0xAD8A65FF, 0x77377BFF, 0x33D8C9FF, 0x2B7CC9FF, 0xF5B43DFF,
            0xC85B67FF, 0x379B2EFF, 0xF32FEDFF, 0xACF7A5FF, 0x194847FF, 0x909DCEFF, 0x7041C7FF, 0xCD1D7DFF,
            0x78752CFF, 0xF2C2A4FF, 0x4C173DFF, 0xBF68C0FF, 0xCD9327FF, 0x95403AFF, 0xCB20C5FF, 0x76D973FF,
            0x53859AFF, 0x3A1E7EFF, 0xDE5C23FF, 0xE7F83EFF, 0xB6A66BFF, 0x2B0B16FF, 0x8D4F87FF, 0x3EF0E9FF,
            0xE01A2CFF, 0x398CF1FF, 0x4A1CE2FF, 0xCD717CFF, 0x9B1E90FF, 0x6AB634FF, 0x2B6D54FF, 0xA5B8EEFF,
            0x110F3AFF, 0x8A5FCCFF, 0xEF2584FF, 0x8E8C2EFF, 0xECDEA6FF, 0x63344DFF, 0xC885D4FF, 0x991FC6FF,
            0xD7AD2AFF, 0xA75744FF, 0xE559C6FF, 0x64F888FF, 0x619CA3FF, 0x655194FF, 0xED7228FF, 0xBB224CFF,
            0xC9BD89FF, 0x452515FF, 0xB36286FF, 0xF5332BFF, 0x44A4F3FF, 0x5244EAFF, 0x84451FFF, 0xF1897EFF,
            0xB64C8FFF, 0x70CD38FF, 0x568A70FF, 0x8CD6ECFF, 0x1D3665FF, 0x937DEAFF, 0xF3668BFF, 0x97A536FF,
            0x8F5A72FF, 0xE98DEEFF, 0xB024EEFF, 0x1F359CFF, 0xB5704CFF, 0x8B1C63FF, 0xEB6EDCFF, 0x256323FF,
            0x4FBFB0FF, 0x6864A0FF, 0xD24059FF, 0xA0C4AEFF, 0x1C1EE8FF, 0x26491BFF, 0xA67FAAFF, 0x823DA9FF,
            0x37CCF1FF, 0x3771EDFF, 0x965B20FF, 0xF1A385FF, 0x52111AFF, 0xA9A385FF, 0x4AF53BFF, 0x39A67BFF,
            0x94F3EAFF, 0x215175FF, 0x828CF3FF, 0x7521E8FF, 0x9A2418FF, 0x9FBC3AFF, 0xA27A83FF, 0xDEAFEBFF,
            0x511671FF, 0xA95CECFF, 0x2751B4FF, 0xCF8A58FF, 0x7D5054FF, 0x297B27FF, 0x77D6B5FF, 0x6073BBFF,
            0x4B1BA9FF, 0xE87461FF, 0xE5F182FF, 0x525324FF, 0xC396BEFF, 0x8B52A8FF, 0x9F6D24FF, 0x74181FFF,
            0xC581A7FF, 0x3BB678FF, 0x27648BFF, 0x7C51EBFF, 0xB13F25FF, 0xDF4C9EFF, 0xA0DD3AFF, 0x7E946DFF,
            0xEBC4EAFF, 0x5B3883FF, 0xB383F2FF, 0x286BBBFF, 0xDDA562FF, 0x8D6562FF, 0x378A33FF, 0xD548ECFF,
            0x5AF6C2FF, 0x708EC7FF, 0x543CB3FF, 0xF06468FF, 0xA8246BFF, 0x596D2BFF, 0xCBA4BCFF, 0x35134AFF,
            0xA762AFFF, 0xF42E5CFF, 0xB38427FF, 0x803B47FF, 0xEF78B9FF, 0xAF45B2FF, 0x3ACD85FF, 0x297894FF,
            0x1B1988FF, 0xC05924FF, 0xED8AA7FF, 0xA7F73FFF, 0x82A77BFF, 0x844A6FFF, 0xBE2123FF, 0x3393CBFF,
            0xD0BA67FF, 0x1D1AB9FF, 0xAE6D80FF, 0x6E456CFF, 0x31AD2DFF, 0x246161FF, 0x7AAFE2FF, 0x5E5AC9FF,
            0xB7466FFF, 0x6A8B32FF, 0xAFDCB8FF, 0x453863FF, 0x9C73D1FF, 0x731EB9FF, 0xB4A62EFF, 0x99685CFF,
            0xF2A1C5FF, 0xAA7FC5FF, 0x38E996FF, 0x2693A1FF, 0x31488BFF, 0x111212FF, 0x951D3FFF, 0x91BC7FFF,
            0x182E3FFF, 0x87668BFF, 0xD2442BFF, 0x3DA9D0FF, 0xDCD17AFF, 0x243FDEFF, 0x61381BFF, 0xC98A8BFF,
            0x857081FF, 0x2EC534FF, 0x2D7F6BFF, 0x74C5DEFF, 0x151764FF, 0x7371EAFF, 0xDB5B84FF, 0x75A33DFF,
            0xDDF8C6FF, 0x55567DFF, 0xAF9CEFFF, 0x9645E3FF, 0xC6C634FF, 0x8A684EFF, 0x6C1755FF, 0xC970EBFF,
            0x32AEADFF, 0x486597FF, 0xF29032FF, 0xB0565CFF, 0xA3D385FF, 0x1B3216FF, 0x8674ACFF, 0x6F1A8CFF,
            0xF06041FF, 0x32B8E5FF, 0x265AE8FF, 0x725A24FF, 0xD1A391FF, 0x948C7FFF, 0x38DE39FF, 0x29936AFF,
            0x5C1AD9FF, 0x711E1AFF, 0xF07095FF, 0x85B557FF, 0x5C6365FF, 0xC7B7EBFF, 0x2C054DFF, 0x9C59E5FF,
            0xFC2091FF, 0xDAD818FF, 0xA9885BFF, 0x70365EFF, 0xE682E6FF, 0x60C8B0FF, 0x4A71AEFF, 0xFAA737FF,
            0xBD565BFF, 0xF93CDFFF, 0xBEEF9DFF, 0x31432FFF, 0x989BADFF, 0x6D41A3FF, 0x3079FDFF, 0x7C681BFF,
            0xE9BFA0FF, 0x3F1A28FF, 0xB569A8FF, 0x33FB52FF, 0x25A775FF, 0x165271FF, 0x84A9FFFF, 0x6943F0FF,
            0x8A3923FF, 0xC426A0FF, 0x90CF5DFF, 0x6B7D72FF, 0xD5D2F8FF, 0x3F2864FF, 0xAC76F7FF, 0xE9F211FF,
            0xBAA163FF, 0x854F6DFF, 0xF89DF4FF, 0xC232EDFF, 0x68E3BBFF, 0x548CBFFF, 0x3D29A9FF, 0xD27066FF,
            0x8E0765FF, 0x3F5C3BFF, 0xA6B5BAFF, 0x130B27FF, 0x7D5DB6FF, 0xDE2E65FF, 0x8D8120FF, 0xF9D9A8FF,
            0x3C17F7FF, 0x553336FF, 0xC884B6FF, 0x8F1CABFF, 0x2AC27FFF, 0x1D6D82FF, 0x170562FF, 0xA1532BFF,
            0xDB49B0FF, 0x9CE962FF, 0x78967EFF, 0x4F4278FF, 0x9013F8FF, 0xA7082EFF, 0xCABB6BFF, 0x98697BFF,
            0x6FFEC5FF, 0x5CA7CFFF, 0x4849C0FF, 0xE6896FFF, 0xA63175FF, 0x4C7645FF, 0xB3CFC5FF, 0x24253DFF,
            0x8C78C7FF, 0xF74E70FF, 0x9D9B24FF, 0x694C43FF, 0xD99EC3FF, 0xA440BEFF, 0x2FDC88FF, 0x248791FF,
            0x1C2C7CFF, 0xB66C31FF, 0x71173DFF, 0xF066BFFF, 0x85B089FF, 0x5F5C89FF, 0xC13337FF, 0xD9D571FF,
            0x1028C5FF, 0x3B2C0CFF, 0xA98288FF, 0x72297FFF, 0x64C1DDFF, 0x5166D4FF, 0xFAA377FF, 0xBD4E83FF,
            0x578F4FFF, 0xBFEAD0FF, 0x323E4FFF, 0x9B92D7FF, 0x712DC7FF, 0xACB426FF, 0x7B654FFF, 0xE9B8CFFF,
            0x401042FF, 0xB75ED0FF, 0x33F790FF, 0x2AA29FFF, 0x224992FF, 0xC98637FF, 0x8A344BFF, 0x90CA93FF,
            0x041D1AFF, 0x6D7699FF, 0x431382FF, 0xD9503FFF, 0xE8EF76FF, 0x4E4515FF, 0xBA9C93FF, 0x864691FF,
            0x6CDCEAFF, 0x5A81E7FF, 0x561817FF, 0xD26991FF, 0x62A957FF, 0x40575FFF, 0xA8ADE6FF, 0x814EDCFF,
            0xDE1D8BFF, 0xBACE27FF, 0x8C7E59FF, 0xF9D3DBFF, 0x562C55FF, 0xC979E0FF, 0x2FBCACFF, 0x2864A6FF,
            0xDC9F3CFF, 0xA04E58FF, 0x338607FF, 0xDC35D8FF, 0x9BE59CFF, 0x123629FF, 0x7A90A8FF, 0x533599FF,
            0xF06B46FF, 0x605E1DFF, 0xCAB69EFF, 0x26111FFF, 0x9960A1FF, 0x72F7F6FF, 0xFB1A4AFF, 0x639DF9FF,
            0x5133E5FF, 0x6E3221FF, 0xE6839DFF, 0xA71F98FF, 0x6CC45EFF, 0x4C716DFF, 0xB5C8F4FF, 0x281A58FF,
            0x906BEFFF, 0xF74499FF, 0xC8E926FF, 0x9C9762FF, 0x694666FF, 0xDB94EFFF, 0xA726E4FF, 0x33D7B8FF,
            0x2D7FB8FF, 0xEEB93FFF, 0x28149CFF, 0xB56863FF, 0x3CA00CFF, 0xF257E8FF, 0xA5FFA4FF, 0x1E5036FF,
            0x86AAB6FF, 0x6251ADFF, 0xC12B60FF, 0x707723FF, 0xD9D0A7FF, 0x3B292FFF, 0xAB7BB1FF, 0x750BA0FF,
            0x5A55FBFF, 0x844B2AFF, 0xF99DA8FF, 0xBE42A9FF, 0x76DE64FF, 0x588B7AFF, 0x36366EFF, 0x8A032BFF,
            0xABB16BFF, 0x7C5F75FF, 0xEBAFFDFF, 0xBA4CF7FF, 0x36F2C3FF, 0x339AC8FF, 0xFFD341FF, 0x2E3AB4FF,
            0xC9816DFF, 0x8A2A6DFF, 0x44BB0CFF, 0x286942FF, 0x92C5C2FF, 0x0B1731FF, 0x706CC0FF, 0xD9496CFF,
            0x7F9029FF, 0xE7EAB0FF, 0x4E413EFF, 0xBB95BFFF, 0x8936B5FF, 0x996432FF, 0x561036FF, 0xD35FB9FF,
            0x7FF96AFF, 0x63A586FF, 0x435081FF, 0xA42E35FF, 0xB9CB72FF, 0x232109FF, 0x8D7983FF, 0x581F74FF,
            0x37B5D8FF, 0x3458CBFF, 0xDB9B76FF, 0xA0477DFF, 0x4BD509FF, 0x32834CFF, 0x9CDFCEFF, 0x163146FF,
            0x7D87D1FF, 0x591CBBFF, 0xF06577FF, 0x8CAA2DFF, 0x5F5A4BFF, 0xCBAFCCFF, 0x9B54C8FF, 0xAC7D39FF,
            0x6E2D45FF, 0xE77AC8FF, 0x6DBF91FF, 0x4F6A92FF, 0xBC4A3EFF, 0xF832C1FF, 0xC7E578FF, 0x343914FF,
            0x9C9290FF, 0x6C3C88FF, 0x3BD0E6FF, 0x3974DFFF, 0xEDB57EFF, 0x3B1012FF, 0xB5618CFF, 0x3B9E56FF,
            0xA6FAD8FF, 0x214B57FF, 0x89A2E0FF, 0x6841D2FF, 0xC11985FF, 0x9AC430FF, 0x6F7456FF, 0xDAC9D8FF,
            0x3D224CFF, 0xAD70D9FF, 0xBE963FFF, 0x844653FF, 0xFA95D5FF, 0xC02CCFFF, 0x76DA9BFF, 0x5B85A2FF,
            0x3C278DFF, 0xD36446FF, 0xD3FF7EFF, 0x44521DFF, 0xABAC9BFF, 0x0F0513FF, 0x7D569AFF, 0x3DEBF3FF,
            0xDC1A48FF, 0xFECF86FF, 0x3B1CD8FF, 0x53291EFF, 0xC97B99FF, 0x8C158FFF, 0x42B85EFF, 0x2B6567FF,
            0x95BDEFFF, 0x140649FF, 0x755FE7FF, 0xD93F94FF, 0xA6DE31FF, 0x7E8D60FF, 0xE8E4E3FF, 0x4F3B5EFF,
            0xBD8BE9FF, 0x8D14D9FF, 0xCFB043FF, 0x98605FFF, 0xD54FE1FF, 0x7EF4A4FF, 0x659FB1FF, 0x4945A3FF,
            0xE87E4DFF, 0xA4265BFF, 0x526C25FF, 0xB9C6A5FF, 0x231E27FF, 0x8E71AAFF, 0xF64051FF, 0x4145F0FF,
            0x694228FF, 0xDB95A5FF, 0xA23AA2FF, 0x4AD265FF, 0x347F75FF, 0x9FD7FDFF, 0x1D2862FF, 0x827BF9FF,
            0xF05CA2FF, 0xB2F931FF, 0x8CA76AFF, 0xF5FEEDFF, 0x60556FFF, 0xCDA6F8FF, 0x9F40EEFF, 0xBF210EFF,
            0xDFCA47FF, 0x1428A8FF, 0xAC796AFF, 0x6F2265FF, 0xE96DF2FF, 0x6FB9BEFF, 0x5460B7FF, 0xFC9853FF,
            0xBC4468FF, 0x60852BFF, 0xC6E0AFFF, 0x0A0FF5FF, 0x343637FF, 0x9E8BB9FF, 0x6F2AABFF, 0x7D5B31FF,
            0xEDAFB0FF, 0x3B062CFF, 0xB757B3FF, 0x50ED6CFF, 0x3C9982FF, 0x274377FF, 0x6F22F8FF, 0x872932FF,
            0x99C172FF, 0x0B1405FF, 0x706E7EFF, 0x401068FF, 0xD84213FF, 0xEEE44AFF, 0x0B49C0FF, 0xBD9274FF,
            0x853E76FF, 0x78D4CAFF, 0x5F7BC9FF, 0xD25F74FF, 0x6C9F31FF, 0xD3FBB8FF, 0x444F45FF, 0xADA5C7FF,
            0x8149BFFF, 0x8F7439FF, 0xFEC9BAFF, 0x53243EFF, 0xCA72C2FF, 0x44B48EFF, 0x305E8AFF, 0x9F433CFF,
            0xDA2CBAFF, 0xA5DB79FF, 0x192D11FF, 0x7F888BFF, 0x52307EFF, 0xF05E16FF, 0xFDFE4BFF, 0xCEAC7EFF,
            0x21070CFF, 0x995886FF, 0x80EFD6FF, 0x6A96DAFF, 0x5032C7FF, 0xE7797FFF, 0xA4127DFF, 0x78B935FF,
            0x526852FF, 0xBABFD4FF, 0x251640FF, 0x9165D1FF, 0xF5367DFF, 0xA08D40FF, 0x693D4DFF, 0xDC8DD0FF,
            0xA422C6FF, 0x4ACE99FF, 0x39789BFF, 0x271580FF, 0xB55D45FF, 0xF14FCBFF, 0xB0F580FF, 0x27461BFF,
            0x8CA298FF, 0x634C92FF, 0xBE1845FF, 0xDEC686FF, 0x392019FF, 0xAC7294FF, 0x73B1EAFF, 0x5B52DDFF,
            0xFB9388FF, 0xBC398EFF, 0x83D438FF, 0x5F825EFF, 0xC8DAE0FF, 0x363055FF, 0xA181E3FF, 0xB0A746FF,
            0x7D565BFF, 0xEEA7DEFF, 0xB946D9FF, 0x50E9A2FF, 0x4093ABFF, 0x2F3798FF, 0xCA774DFF, 0x872054FF,
            0x336024FF, 0x99BCA3FF, 0x0C111CFF, 0x7266A3FF, 0xD83C4FFF, 0xEEE08DFF, 0x2832E4FF, 0x4E3825FF,
            0xBE8CA1FF, 0x873199FF, 0x7CCCF8FF, 0x666FF1FF, 0xD2569CFF, 0x8DEE3AFF, 0x6C9C68FF, 0xD4F4EBFF,
            0x454967FF, 0xAF9CF2FF, 0x8533E4FF, 0xA11E12FF, 0xC0C04BFF, 0x8F7067FF, 0xFEC2EAFF, 0x55195BFF,
            0xCC65EBFF, 0x48ADB9FF, 0x3853AEFF, 0xDE9054FF, 0x9F3D63FF, 0x3E7A2CFF, 0xA5D6ADFF, 0x1A2A2FFF,
            0x8180B3FF, 0x571B9FFF, 0xEF5958FF, 0xFCFA93FF, 0x2655FBFF, 0x61512FFF, 0xCFA6ADFF, 0x9B4EABFF,
            0x6B222DFF, 0xE871AAFF, 0x77B671FF, 0x536377FF, 0x9655F9FF, 0xBB3D18FF, 0xF524A5FF, 0xCEDA4FFF,
            0xA08972FF, 0x6A356EFF, 0xDF81FBFF, 0x4EC8C6FF, 0x406FC1FF, 0xF0AA5AFF, 0xB55870FF, 0x499433FF,
            0xF33AF3FF, 0xB0F0B7FF, 0x27433FFF, 0x8E9BC2FF, 0x673DB5FF, 0x736A38FF, 0xDFC0B8FF, 0x3A1B35FF,
            0xAE69BCFF, 0x833C39FF, 0xFC8CB6FF, 0xBE25B3FF, 0x82D079FF, 0x617D86FF, 0x3A2473FF, 0xD3581DFF,
            0xDCF552FF, 0xB0A37CFF, 0x7E4F7FFF, 0x54E3D3FF, 0x478AD3FF, 0x3A1FBAFF, 0xCA727BFF, 0x880575FF,
            0x53AE39FF, 0x335C4DFF, 0x9BB5D0FF, 0x110431FF, 0x765AC9FF, 0xD73378FF, 0x838340FF, 0xEEDAC2FF,
            0x4F3446FF, 0xBF84CBFF, 0x8A12BCFF, 0x995543FF, 0xD448C4FF, 0x8CEA81FF, 0x6D9793FF, 0x494088FF,
            0xE97221FF, 0xA11541FF, 0xBFBC85FF, 0x211613FF, 0x90698EFF, 0x59FEDEFF, 0xF42E2FFF, 0x4EA5E3FF,
            0x4243D2FF, 0xDD8B86FF, 0xA03286FF, 0x5CC83DFF, 0x3F765AFF, 0xA7CFDDFF, 0x1E234AFF, 0x8476DBFF,
            0xEF5185FF, 0x929D48FF, 0xFCF5CBFF, 0x614C55FF, 0xD09ED9FF, 0x9E3CD1FF, 0x16268BFF, 0xAD6F4CFF,
            0x6B194CFF, 0xE966D4FF, 0x78B1A0FF, 0x575A9BFF, 0xFE8C23FF, 0xBA384CFF, 0xCDD68DFF, 0x0E18D6FF,
            0x352D20FF, 0xA1839CFF, 0x6C268FFF, 0x54C0F3FF, 0x4961E7FF, 0xF0A590FF, 0xB64F96FF, 0x64E341FF,
            0x499065FF, 0xB2EAE8FF, 0x2A3D5EFF, 0x9291ECFF, 0x6D23D9FF, 0x841913FF, 0xA0B64EFF, 0x736662FF,
            0xE0B9E6FF, 0x3C0C4FFF, 0xB05BE3FF, 0x1745A3FF, 0xC08854FF, 0x83365CFF, 0xFD82E2FF, 0x0E6D2BFF,
            0x82CBABFF, 0x6475ACFF, 0xD25357FF, 0xDBF194FF, 0x46462CFF, 0xB19DA9FF, 0x8044A3FF, 0x517EFBFF,
            0x501B27FF, 0xCA6AA5FF, 0x6BFD43FF, 0x52AA6FFF, 0x365770FF, 0x9EACFCFF, 0x7C48EFFF, 0x9E371BFF,
            0xD81F9EFF, 0xADD053FF, 0x837F6EFF, 0xEFD3F3FF, 0x502B64FF, 0xC278F5FF, 0x1861B8FF, 0xD2A25BFF,
            0x99506BFF, 0x188733FF, 0xD631EBFF, 0x8CE6B5FF, 0x6F8FBCFF, 0x4F30AAFF, 0xE86E60FF, 0x565F36FF,
            0xC0B7B5FF, 0x22102AFF, 0x925FB4FF, 0xF32460FF, 0xA48203FF, 0x4D26F7FF, 0x683434FF, 0xDE84B2FF,
            0xA21BAAFF, 0x5BC578FF, 0x417180FF, 0x241365FF, 0xB55221FF, 0xF045ADFF, 0xBAEB57FF, 0x929979FF,
            0xFDEEFEFF, 0x634577FF, 0xA21AF7FF, 0x197DCBFF, 0xE3BB62FF, 0xAD6977FF, 0x20A23AFF, 0xEB55FDFF,
            0x7BAACBFF, 0x5C4EBFFF, 0xFD8868FF, 0xBA2E72FF, 0x657840FF, 0xCED1C0FF, 0x35293DFF, 0xA37AC5FF,
            0xB59C00FF, 0x7D4D40FF, 0xF09EBFFF, 0xB740BCFF, 0x63DF81FF, 0x4B8B8EFF, 0x30337DFF, 0xCB6B26FF,
            0x84103CFF, 0xA0B283FF, 0x0A0A0AFF, 0x745F88FF, 0xD62C30FF, 0x1998DDFF, 0xF3D567FF, 0x2932C6FF,
            0xC08383FF, 0x842A7EFF, 0x27BC40FF, 0x116A55FF, 0x85C4D8FF, 0x04143CFF, 0x686AD3FF, 0xD24C80FF,
            0x739248FF, 0xDBEBCAFF, 0x47424EFF, 0xB294D4FF, 0x8331C7FF, 0x90664AFF, 0x511043FF, 0xCC5ECDFF,
            0x54A59BFF, 0x3B4E92FF, 0xE0852AFF, 0x9D3248FF, 0xADCC8CFF, 0x1C221AFF, 0x847997FF, 0x541984FF,
            0xEF4B36FF, 0x18B4EDFF, 0x2B53DDFF, 0x624707FF, 0xD29D8EFF, 0x9A468FFF, 0x2CD745FF, 0x1A8461FF,
            0x8EDFE5FF, 0x092F53FF, 0x7485E5FF, 0x681412FF, 0xE8678CFF, 0x80AC4FFF, 0x575B5DFF, 0xC1AFE2FF,
            0x9551DBFF, 0xF30888FF, 0xA37F53FF, 0x682D55FF, 0xDF7ADDFF, 0x5DC0A8FF, 0x4669A5FF, 0xF49F2DFF,
            0xB54D54FF, 0xF131D5FF, 0xB9E795FF, 0x2B3A27FF, 0x9392A4FF, 0x663999FF, 0x16CFFCFF, 0x2D70F2FF,
            0x755F0FFF, 0xE3B798FF, 0x361220FF, 0xAE619FFF, 0x31F249FF, 0x219E6DFF, 0x97FAF1FF, 0x114A67FF,
            0x7FA0F6FF, 0x633AE4FF, 0x81311BFF, 0xFD8298FF, 0xBB1997FF, 0x8CC655FF, 0x65746AFF, 0xCFC9EFFF,
            0x381F59FF, 0xA66DEDFF, 0xB4995BFF, 0x7D4764FF, 0xF295EBFF, 0xBA26E3FF, 0x64DAB3FF, 0x4F83B6FF,
            0x381F9DFF, 0xCA675EFF, 0x3A5333FF, 0xA1ADB1FF, 0x7755ACFF, 0xD5225DFF, 0x877914FF, 0xF3D1A0FF,
            0x4D2B2EFF, 0xC17CADFF, 0x860CA0FF, 0x27B977FF, 0x6DFA89FF, 0x196479FF, 0x3E91F1FF, 0x6F5AFAFF,
    };


    /**
     * This 255-color (plus transparent) palette uses the (3,5,7) Halton sequence to get 3D points, treats those as IPT
     * channel values, and rejects out-of-gamut colors. This also rejects any color that is too similar to an existing
     * color, which in this case made this try 130958 colors before finally getting 256 that work. Using the Halton
     * sequence provides one of the stronger guarantees that removing any sequential items (after the first 9, which are
     * preset grayscale colors) will produce a similarly-distributed palette. Typically, 64 items from this are enough
     * to make pixel art look good enough with dithering, and it continues to improve with more colors. It has exactly 8
     * colors that are purely grayscale, all right at the start after transparent.
     * <br>
     * Haltonic was the default palette from a fairly early version until 0.3.9, when it was replaced with Aurora.
     */
    public static final int[] HALTONIC = new int[]{
            0x00000000, 0x010101FF, 0xFEFEFEFF, 0x7B7B7BFF, 0x555555FF, 0xAAAAAAFF, 0x333333FF, 0xE0E0E0FF,
            0xC8C8C8FF, 0xBEBB4EFF, 0x1FAE9AFF, 0xC2BBA9FF, 0xB46B58FF, 0x7C82C2FF, 0xF2825BFF, 0xD55193FF,
            0x8C525CFF, 0x6AEF59FF, 0x1F439BFF, 0x793210FF, 0x3B3962FF, 0x16D72EFF, 0xB53FC6FF, 0xB380C7FF,
            0xEDE389FF, 0x8420C6FF, 0x291710FF, 0x69D4D3FF, 0x76121CFF, 0x1FA92AFF, 0x64852CFF, 0x7A42DBFF,
            0xEA5A5EFF, 0x7E3E8CFF, 0xB8FA35FF, 0x4F15DAFF, 0xBC3E61FF, 0xA19150FF, 0x9BBD25FF, 0xF095C2FF,
            0xFFC24FFF, 0x7B7CFCFF, 0x9BE8C3FF, 0xE25EC4FF, 0x3D79ADFF, 0xC0422AFF, 0x260E5DFF, 0xF645A3FF,
            0xF8ACE4FF, 0xB0871FFF, 0x42582CFF, 0x549787FF, 0xE31BA2FF, 0x1E222AFF, 0xB39CF5FF, 0x8C135FFF,
            0x71CB92FF, 0xB767B3FF, 0x7E5030FF, 0x406697FF, 0x502B06FF, 0xDFAC73FF, 0xC21A26FF, 0xECFE65FF,
            0x7E64E4FF, 0xBFD22EFF, 0xDA938FFF, 0x8E94E8FF, 0xA0DE92FF, 0x8C6BA9FF, 0x1662FCFF, 0xCA4EECFF,
            0x8899AAFF, 0x24BC57FF, 0x680AA7FF, 0xFE6885FF, 0x2E1E6EFF, 0x875695FF, 0x981C20FF, 0x47723EFF,
            0xF4E54FFF, 0x71174CFF, 0xC5F8ABFF, 0x75BFC7FF, 0xF23C37FF, 0xFC73E9FF, 0x893A5FFF, 0x4F50C5FF,
            0xE06635FF, 0xB00D9FFF, 0xE90FCAFF, 0x1E9CFBFF, 0x3538F9FF, 0xE3971BFF, 0x500153FF, 0x2DB2CEFF,
            0xB46D86FF, 0xFE43F2FF, 0x4FF990FF, 0x434531FF, 0xE31515FF, 0xDFA24BFF, 0x4282E6FF, 0x56626FFF,
            0xF8B891FF, 0x4B0932FF, 0xD769E6FF, 0x906D1DFF, 0xD51144FF, 0x76B6F8FF, 0x4DF7ECFF, 0x169355FF,
            0xB7C87DFF, 0x650C83FF, 0x0AE930FF, 0xEDB71AFF, 0x78AE77FF, 0x081236FF, 0x25E5F4FF, 0x5A4382FF,
            0xB1FEFAFF, 0xEA7B0BFF, 0xF372C1FF, 0xA31479FF, 0x3EDB6AFF, 0xA44210FF, 0xB2C1FAFF, 0xAE9784FF,
            0xE83175FF, 0xF925DFFF, 0xAB134FFF, 0xC03E83FF, 0x117F76FF, 0xE6E21DFF, 0x6B3858FF, 0x88ED12FF,
            0x3E3486FF, 0x3DBB14FF, 0xD35521FF, 0xC2836DFF, 0x244E65FF, 0xAC29F6FF, 0xE71A58FF, 0x1127ABFF,
            0xD086E0FF, 0x496B1CFF, 0xD27E96FF, 0x87353AFF, 0xD308EDFF, 0x5D3BAAFF, 0x11560BFF, 0x469AC6FF,
            0xEDD4B9FF, 0xA4A222FF, 0x48A75CFF, 0xBB7213FF, 0xFBBAFAFF, 0x794811FF, 0x83804EFF, 0xB1FB85FF,
            0x61C56DFF, 0x9D36B1FF, 0x201693FF, 0x184BB9FF, 0x5B0606FF, 0xAB5692FF, 0x090B23FF, 0xA7593AFF,
            0x14D7ADFF, 0xAC6BF1FF, 0xCC0E7EFF, 0x1B90B4FF, 0xA5A94CFF, 0x264509FF, 0xE994FDFF, 0xC1E367FF,
            0x1D16D5FF, 0x1C5C7DFF, 0xCF794CFF, 0xF6FF95FF, 0x7B1A88FF, 0x68B69CFF, 0xAADAF7FF, 0x6625E1FF,
            0x223308FF, 0x7147FEFF, 0xDF6A7FFF, 0xF5FE22FF, 0xB6B1D2FF, 0x35E986FF, 0x2C69D4FF, 0x6D63C8FF,
            0x32042DFF, 0xF4A293FF, 0x22040DFF, 0xF2FAC2FF, 0xFFBBB2FF, 0x9D3F7CFF, 0x86694EFF, 0xD34B57FF,
            0x5B2E24FF, 0xF2CF80FF, 0x10EBAFFF, 0x7B603CFF, 0xFDE5A7FF, 0xB41808FF, 0xA83F4BFF, 0xC221B4FF,
            0x9604A4FF, 0x878287FF, 0x3F1C16FF, 0x5AA7FEFF, 0x55096CFF, 0x1E9922FF, 0x031050FF, 0xA284A1FF,
            0x2424EDFF, 0x8FD111FF, 0x480C8BFF, 0x71FE60FF, 0xFE1D02FF, 0xFF9A60FF, 0xD44ABEFF, 0xFE7B9AFF,
            0x68915EFF, 0x9EFFD1FF, 0xABAC7CFF, 0x4413BFFF, 0xF93E83FF, 0x7A9633FF, 0xA05B73FF, 0x83A3C3FF,
            0x124D4AFF, 0x397E0EFF, 0x6AFEB5FF, 0x975813FF, 0xFEC704FF, 0xBC1462FF, 0xA008E0FF, 0x418886FF,
            0x58CAFEFF, 0x4E7A53FF, 0x7A07FFFF, 0x8D4EBCFF, 0xFE3257FF, 0xA46BD5FF, 0xB079FFFF, 0x909478FF,
            0xFC6C42FF, 0x5F3342FF, 0x6A6A9DFF, 0xFF6315FF, 0x9D56D2FF, 0x6782A7FF, 0x957F24FF, 0xD08FB9FF,
    };

    /**
     * Converts an RGBA8888 int color to the RGB555 format used by {@link #OKLAB} to look up colors.
     * @param color an RGBA8888 int color
     * @return an RGB555 int color
     */
    public static int shrink(final int color)
    {
        return (color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F);
    }

    /**
     * Converts an RGB555 int color to an approximation of the closest RGBA8888 color. For each 5-bit channel in
     * {@code color}, this gets an 8-bit value by keeping the original 5 in the most significant 5 places, then copying
     * the most significant 3 bits of the RGB555 color into the least significant 3 bits of the 8-bit value. In
     * practice, this means the lowest 5-bit value produces the lowest 8-bit value (00000 to 00000000), and the highest
     * 5-bit value produces the highest 8-bit value (11111 to 11111111). This always assigns a fully-opaque value to
     * alpha (255, or 0xFF).
     * @param color an RGB555 color
     * @return an approximation of the closest RGBA8888 color; alpha is always fully opaque
     */
    public static int stretch(final int color)
    {
        return (color << 17 & 0xF8000000) | (color << 12 & 0x07000000) | (color << 14 & 0xF80000) | (color << 9 & 0x070000) | (color << 11 & 0xF800) | (color << 6 & 0x0700) | 0xFF;
    }

    /**
     * Changes the curve of a requested L value so that it matches the internally-used curve. This takes a curve with a
     * very-dark area similar to sRGB (a very small one), and makes it significantly larger. This is typically used on
     * "to Oklab" conversions.
     * <br>
     * Internally, this is similar to {@code (float)Math.pow(L, 1.5f)}. At one point it used a modified "Barron spline"
     * to get its curvature mostly right, but this now seems nearly indistinguishable from an ideal curve.
     * @param L lightness, from 0 to 1 inclusive
     * @return an adjusted L value that can be used internally
     */
    public static float forwardLight(final float L) {
        return (float) Math.sqrt(L * L * L);
    }

    /**
     * Changes the curve of the internally-used lightness when it is output to another format. This makes the very-dark
     * area smaller, matching (closely) the curve that the standard sRGB lightness uses. This is typically used on "from
     * Oklab" conversions.
     * <br>
     * Internally, this is similar to {@code (float)Math.pow(L, 2f/3f)}. At one point it used a modified "Barron spline"
     * to get its curvature mostly right, but this now seems nearly indistinguishable from an ideal curve.
     * <br>
     * This specific code uses a modified cube root approximation (based on {@link OtherMath#cbrtPositive(float)})
     * originally by Marc B. Reynolds.
     * @param L lightness, from 0 to 1 inclusive
     * @return an adjusted L value that can be fed into a conversion to RGBA or something similar
     */
    public static float reverseLight(float L) {
        int ix = NumberUtils.floatToRawIntBits(L);
        final float x0 = L;
        ix = (ix>>>2) + (ix>>>4);
        ix += (ix>>>4);
        ix += (ix>>>8) + 0x2A5137A0;
        L  = NumberUtils.intBitsToFloat(ix);
        L  = 0.33333334f * (2f * L + x0/(L*L));
        L  = 0.33333334f * (1.9999999f * L + x0/(L*L));
        return L * L;
    }

//    /**
//     * Changes the curve of a requested L value so that it matches the internally-used curve. This takes a curve with a
//     * very-dark area similar to sRGB (a very small one), and makes it significantly larger. This is typically used on
//     * "to Oklab" conversions.
//     * @param L lightness, from 0 to 1 inclusive
//     * @return an adjusted L value that can be used internally
//     */
//    public static float forwardLight(final float L) {
//        final float shape = 0.64516133f, turning = 0.95f;
//        final float d = turning - L;
//        float r;
//        if(d < 0)
//            r = ((1f - turning) * (L - 1f)) / (1f - (L + shape * d)) + 1f;
//        else
//            r = (turning * L) / (1e-20f + (L + shape * d));
//        return r * r;
//    }
//
////	public static float forwardLight(final float L) {
////		return (L - 1.004f) / (1f - L * 0.4285714f) + 1.004f;
////	}
//
//    /**
//     * Changes the curve of the internally-used lightness when it is output to another format. This makes the very-dark
//     * area smaller, matching (kind-of) the curve that the standard sRGB lightness uses. This is typically used on "from
//     * Oklab" conversions.
//     * @param L lightness, from 0 to 1 inclusive
//     * @return an adjusted L value that can be fed into a conversion to RGBA or something similar
//     */
//    public static float reverseLight(float L) {
//        L = (float) Math.sqrt(L);
//        final float shape = 1.55f, turning = 0.95f;
//        final float d = turning - L;
//        float r;
//        if(d < 0)
//            r = ((1f - turning) * (L - 1f)) / (1f - (L + shape * d)) + 1f;
//        else
//            r = (turning * L) / (1e-20f + (L + shape * d));
//        return r;
//    }
//
////	public static float reverseLight(final float L) {
////		return (L - 0.993f) / (1f + L * 0.75f) + 0.993f;
////	}

    /**
     * Stores Oklab components corresponding to RGB555 indices.
     * OKLAB[0] stores L (lightness) from 0.0 to 1.0 .
     * OKLAB[1] stores A, which is something like a green-red axis, from -0.5 (green) to 0.5 (red).
     * OKLAB[2] stores B, which is something like a blue-yellow axis, from -0.5 (blue) to 0.5 (yellow).
     * OKLAB[3] stores the hue in radians from -PI to PI, with red at 0, yellow at PI/2, and blue at -PI/2.
     * <br>
     * The indices into each of these float[] values store red in bits 10-14, green in bits 5-9, and blue in bits 0-4.
     * It's ideal to work with these indices with bitwise operations, as with {@code (r << 10 | g << 5 | b)}, where r,
     * g, and b are all in the 0-31 range inclusive. It's usually easiest to convert an RGBA8888 int color to an RGB555
     * color with {@link #shrink(int)}.
     */
    public static final float[][] OKLAB = new float[4][0x8000];

    /**
     * A 4096-element byte array as a 64x64 grid of bytes. When arranged into a grid, the bytes will follow a blue noise
     * frequency (in this case, they will have a triangular distribution for its bytes, so values near 0 are much more
     * common). This is used inside this library to create {@link #TRI_BLUE_NOISE_MULTIPLIERS}, which is used in
     * {@link #reduceScatter(Pixmap)}. It is also used directly by {@link #reduceBlueNoise(Pixmap)},
     * {@link #reduceNeue(Pixmap)}, and {@link #reduceChaoticNoise(Pixmap)}.
     * <br>
     * While, for some reason, you could change the contents to some other distribution of bytes, I don't know why this
     * would be needed.
     */
    public static final byte[] TRI_BLUE_NOISE = ConstantData.TRI_BLUE_NOISE;
    /**
     * A 4096-element byte array as a 64x64 grid of bytes. When arranged into a grid, the bytes will follow a blue noise
     * frequency (in this case, they will have a triangular distribution for its bytes, so values near 0 are much more
     * common). This is used inside this library by {@link #reduceBlueNoise(Pixmap)}.
     * <br>
     * While, for some reason, you could change the contents to some other distribution of bytes, I don't know why this
     * would be needed.
     */
    public static final byte[] TRI_BLUE_NOISE_B = ConstantData.TRI_BLUE_NOISE_B;
    /**
     * A 4096-element byte array as a 64x64 grid of bytes. When arranged into a grid, the bytes will follow a blue noise
     * frequency (in this case, they will have a triangular distribution for its bytes, so values near 0 are much more
     * common). This is used inside this library by {@link #reduceBlueNoise(Pixmap)}.
     * <br>
     * While, for some reason, you could change the contents to some other distribution of bytes, I don't know why this
     * would be needed.
     */
    public static final byte[] TRI_BLUE_NOISE_C = ConstantData.TRI_BLUE_NOISE_C;

    /**
     * A 64x64 grid of floats, with a median value of about 1.0, generated using the triangular-distributed blue noise
     * from {@link #TRI_BLUE_NOISE}. If you randomly selected two floats from this and multiplied them, the average
     * result should be 1.0; half of the items in this should be between 1 and {@code 4.232604}, and the other half should
     * be the inverses of the first half (between {@code 0.23626116}, which is {@code 1.0/4.232604}, and 1).
     * <br>
     * While, for some reason, you could change the contents to some other distribution of floats, I don't know why this
     * would be needed.
     */
    public static final float[] TRI_BLUE_NOISE_MULTIPLIERS   = ConstantData.TRI_BLUE_NOISE_MULTIPLIERS;

    /**
     * A 64x64 grid of floats, with a median value of about 1.0, generated using the triangular-distributed blue noise
     * from {@link #TRI_BLUE_NOISE_B}. If you randomly selected two floats from this and multiplied them, the average
     * result should be 1.0; half of the items in this should be between 1 and {@code 4.232604}, and the other half should
     * be the inverses of the first half (between {@code 0.23626116}, which is {@code 1.0/4.232604}, and 1).
     * <br>
     * While, for some reason, you could change the contents to some other distribution of floats, I don't know why this
     * would be needed.
     */

    public static final float[] TRI_BLUE_NOISE_MULTIPLIERS_B = ConstantData.TRI_BLUE_NOISE_MULTIPLIERS_B;
    /**
     * A 64x64 grid of floats, with a median value of about 1.0, generated using the triangular-distributed blue noise
     * from {@link #TRI_BLUE_NOISE_C}. If you randomly selected two floats from this and multiplied them, the average
     * result should be 1.0; half of the items in this should be between 1 and {@code 4.232604}, and the other half should
     * be the inverses of the first half (between {@code 0.23626116}, which is {@code 1.0/4.232604}, and 1).
     * <br>
     * While, for some reason, you could change the contents to some other distribution of floats, I don't know why this
     * would be needed.
     */
    public static final float[] TRI_BLUE_NOISE_MULTIPLIERS_C = ConstantData.TRI_BLUE_NOISE_MULTIPLIERS_C;

    public static final float[] TRIANGULAR_BYTE_LOOKUP = new float[256];
    static {
        float rf, gf, bf, lf, mf, sf;
        int idx = 0;
        for (int ri = 0; ri < 32; ri++) {
            rf = (float) (ri * ri * 0.0010405827263267429); // 1.0 / 31.0 / 31.0
            for (int gi = 0; gi < 32; gi++) {
                gf = (float) (gi * gi * 0.0010405827263267429); // 1.0 / 31.0 / 31.0
                for (int bi = 0; bi < 32; bi++) {
                    bf = (float) (bi * bi * 0.0010405827263267429); // 1.0 / 31.0 / 31.0

                    lf = OtherMath.cbrtPositive(0.4121656120f * rf + 0.5362752080f * gf + 0.0514575653f * bf);
                    mf = OtherMath.cbrtPositive(0.2118591070f * rf + 0.6807189584f * gf + 0.1074065790f * bf);
                    sf = OtherMath.cbrtPositive(0.0883097947f * rf + 0.2818474174f * gf + 0.6302613616f * bf);

                    OKLAB[0][idx] = forwardLight(
                                    0.2104542553f * lf + 0.7936177850f * mf - 0.0040720468f * sf);
                    OKLAB[1][idx] = 1.9779984951f * lf - 2.4285922050f * mf + 0.4505937099f * sf;
                    OKLAB[2][idx] = 0.0259040371f * lf + 0.7827717662f * mf - 0.8086757660f * sf;
                    OKLAB[3][idx] = OtherMath.atan2(OKLAB[2][idx], OKLAB[1][idx]);

                    idx++;
                }
            }

            for (int i = 0; i < 256; i++) {
                TRIANGULAR_BYTE_LOOKUP[i] = OtherMath.triangularRemap(i + 0.5f, 256);
            }
        }
//        for (int i = 1; i < 256; i++) {
//            EXACT_LOOKUP[i] = OtherMath.barronSpline(i / 255f, 4f, 0.5f);
//            ANALYTIC_LOOKUP[i] = OtherMath.barronSpline(i / 255f, 3f, 0.5f);
//        }

//
//        double r, g, b, x, y, z;
//        int idx = 0;
//        for (int ri = 0; ri < 32; ri++) {
//            r = ri / 31.0;
//            r = ((r > 0.04045) ? Math.pow((r + 0.055) / 1.055, 2.4) : r / 12.92);
//            for (int gi = 0; gi < 32; gi++) {
//                g = gi / 31.0;
//                g = ((g > 0.04045) ? Math.pow((g + 0.055) / 1.055, 2.4) : g / 12.92);
//                for (int bi = 0; bi < 32; bi++) {
//                    b = bi / 31.0;
//                    b = ((b > 0.04045) ? Math.pow((b + 0.055) / 1.055, 2.4) : b / 12.92);
//
//                    x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.950489; // 0.96422;
//                    y = (r * 0.2126 + g * 0.7152 + b * 0.0722) / 1.000000; // 1.00000;
//                    z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.088840; // 0.82521;
//
//                    x = (x > 0.008856) ? Math.cbrt(x) : (7.787037037037037 * x) + 0.13793103448275862;
//                    y = (y > 0.008856) ? Math.cbrt(y) : (7.787037037037037 * y) + 0.13793103448275862;
//                    z = (z > 0.008856) ? Math.cbrt(z) : (7.787037037037037 * z) + 0.13793103448275862;
//
//                    LAB[0][idx] = (116.0 * y) - 16.0;
//                    LAB[1][idx] = 500.0 * (x - y);
//                    LAB[2][idx] = 200.0 * (y - z);
//                    idx++;
//                }
//            }
//        }
    }

    public static int oklabToRGB(float L, float A, float B, float alpha)
    {
        L = reverseLight(L);
        float l = (L + 0.3963377774f * A + 0.2158037573f * B);
        float m = (L - 0.1055613458f * A - 0.0638541728f * B);
        float s = (L - 0.0894841775f * A - 1.2914855480f * B);
        l *= l * l;
        m *= m * m;
        s *= s * s;
        final int r = (int)(Math.sqrt(Math.min(Math.max(+4.0767245293f * l - 3.3072168827f * m + 0.2307590544f * s, 0.0f), 1.0f)) * 255.999f);
        final int g = (int)(Math.sqrt(Math.min(Math.max(-1.2681437731f * l + 2.6093323231f * m - 0.3411344290f * s, 0.0f), 1.0f)) * 255.999f);
        final int b = (int)(Math.sqrt(Math.min(Math.max(-0.0041119885f * l - 0.7034763098f * m + 1.7068625689f * s, 0.0f), 1.0f)) * 255.999f);
        return r << 24 | g << 16 | b << 8 | (int)(alpha * 255.999f);
    }

    /**
     * Given a non-null Pixmap, this finds the up-to-255 most-frequently-used colors and returns them as an array of
     * RGBA8888 ints. This always reserves space in the returned array for fully-transparent, so there can be in full
     * 256 colors in the returned array. The int array this returns is useful to pass to {@link #exact(int[])} or the
     * constructors that expect an RGBA8888 palette.
     * @param pixmap a non-null Pixmap, often representing or already limited to the desired palette
     * @return an array of between 1 and 256 RGBA8888 ints, representing a palette
     */
    public static int[] colorsFrom(Pixmap pixmap) {
        return colorsFrom(pixmap, 256);
    }

    /**
     * Given a non-null Pixmap, this finds the up-to-{@code limit - 1} most-frequently-used colors and returns them as
     * an array of RGBA8888 ints. This always reserves space in the returned array for fully-transparent, so there can
     * be in full {@code limit} colors in the returned array. The int array this returns is useful to pass to
     * {@link #exact(int[])} or the constructors that expect an RGBA8888 palette.
     * @param pixmap a non-null Pixmap, often representing or already limited to the desired palette
     * @param limit how many colors this can return as an inclusive limit; the actual returned array can be smaller
     * @return an array of between 1 and {@code limit} RGBA8888 ints, representing a palette
     */
    public static int[] colorsFrom(Pixmap pixmap, int limit) {
        int color, colorCount;
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntIntMap counts = new IntIntMap(256);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                color = pixmap.getPixel(x, y) & 0xF8F8F880;
                if ((color & 0x80) != 0) {
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    counts.getAndIncrement(color, 0, 1);
                }
            }
        }
        int cs = counts.size;
        Array<IntIntMap.Entry> es = new Array<>(cs);
        for (IntIntMap.Entry e : counts) {
            IntIntMap.Entry e2 = new IntIntMap.Entry();
            e2.key = e.key;
            e2.value = e.value;
            es.add(e2);
        }
        es.sort(entryComparator);
        colorCount = Math.min(limit, es.size + 1);
        int[] colorArray = new int[colorCount];
        int i = 1;
        for (IntIntMap.Entry e : es) {
            color = e.key;
            colorArray[i] = color;
            if(++i >= limit) break;
        }
        return colorArray;
    }

    /**
     * Stores the byte indices into {@link #paletteArray} (when treated as unsigned; mask with 255) corresponding to
     * RGB555 colors (you can get an RGB555 int from an RGBA8888 int using {@link #shrink(int)}). This is not especially
     * likely to be useful externally except to make a preload code for later usage. If you have a way to write and read
     * bytes from a file, you can calculate a frequently-used palette once using {@link #exact(int[])} or
     * {@link #analyze(Pixmap)}, write this field to file, and on later runs you can load the 32768-element byte array
     * to speed up construction using {@link #PaletteReducer(int[], byte[])}. Editing this field is strongly
     * discouraged; use {@link #exact(int[])} or {@link #analyze(Pixmap)} to set the palette as a whole.
     */
    public final byte[] paletteMapping = new byte[0x8000];
    /**
     * The RGBA8888 int colors this can reduce an image to use. This is public, and since it is an array you can modify
     * its contents, but you should only change this if you know what you are doing. It is closely related to the
     * contents of the {@link #paletteMapping} field, and paletteMapping should typically be changed by
     * {@link #exact(int[])}, {@link #analyze(Pixmap)}, or {@link #loadPreloadFile(FileHandle)}. Because paletteMapping
     * only contains indices into this paletteArray, if paletteArray changes then the closest-color consideration may be
     * altered. This field can be safely altered, usually, by {@link #alterColorsLightness(Interpolation)} or
     * {@link #alterColorsOklab(Interpolation, Interpolation, Interpolation)}.
     */
    public final int[] paletteArray = new int[256];

    /**
     * A FloatArray used as a buffer to store accrued error for error-diffusion dithers.
     * This stores error for the current line's red channel.
     * It is protected so that user code that extends PaletteReducer doesn't need to create its own buffers.
     */
    protected transient FloatArray curErrorRedFloats;
    /**
     * A FloatArray used as a buffer to store accrued error for error-diffusion dithers.
     * This stores error for the next line's red channel.
     * It is protected so that user code that extends PaletteReducer doesn't need to create its own buffers.
     */
    protected transient FloatArray nextErrorRedFloats;
    /**
     * A FloatArray used as a buffer to store accrued error for error-diffusion dithers.
     * This stores error for the current line's green channel.
     * It is protected so that user code that extends PaletteReducer doesn't need to create its own buffers.
     */
    protected transient FloatArray curErrorGreenFloats;
    /**
     * A FloatArray used as a buffer to store accrued error for error-diffusion dithers.
     * This stores error for the next line's green channel.
     * It is protected so that user code that extends PaletteReducer doesn't need to create its own buffers.
     */
    protected transient FloatArray nextErrorGreenFloats;
    /**
     * A FloatArray used as a buffer to store accrued error for error-diffusion dithers.
     * This stores error for the current line's blue channel.
     * It is protected so that user code that extends PaletteReducer doesn't need to create its own buffers.
     */
    protected transient FloatArray curErrorBlueFloats;
    /**
     * A FloatArray used as a buffer to store accrued error for error-diffusion dithers.
     * This stores error for the next line's blue channel.
     * It is protected so that user code that extends PaletteReducer doesn't need to create its own buffers.
     */
    protected transient FloatArray nextErrorBlueFloats;
    /**
     * How many colors are in the palette here; this is at most 256, and typically includes one fully-transparent color.
     */
    public int colorCount;

    /**
     * Determines how strongly to apply noise or other effects during dithering. The neutral value is 1.0f .
     */
    protected float ditherStrength = 1f;
    /**
     * Typically between 0.5 and 1, this should get closer to 1 with larger palette sizes, and closer to 0.5 with
     * smaller palettes. Within anim8-gdx, this is generally calculated with {@code Math.exp(-1.375 / colorCount)}.
     */
    protected float populationBias = 0.5f;

    /**
     * Given by Joel Yliluoma in <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">a dithering article</a>.
     * Must not be modified.
     */
    protected static final int[] thresholdMatrix8 = {
            0, 4, 2, 6,
            3, 7, 1, 5,
    };

    /**
     * Given by Joel Yliluoma in <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">a dithering article</a>.
     * Must not be modified.
     */
    protected static final int[] thresholdMatrix16 = {
            0,  12,   3,  15,
            8,   4,  11,   7,
            2,  14,   1,  13,
            10,  6,   9,   5,
    };

    /**
     * Given by Joel Yliluoma in <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">a dithering article</a>.
     * Must not be modified.
     */
    protected static final int[] thresholdMatrix64 = {
            0,  48,  12,  60,   3,  51,  15,  63,
            32,  16,  44,  28,  35,  19,  47,  31,
            8,  56,   4,  52,  11,  59,   7,  55,
            40,  24,  36,  20,  43,  27,  39,  23,
            2,  50,  14,  62,   1,  49,  13,  61,
            34,  18,  46,  30,  33,  17,  45,  29,
            10,  58,   6,  54,   9,  57,   5,  53,
            42,  26,  38,  22,  41,  25,  37,  21
    };

    /**
     * A temporary 32-element array typically used to store colors or palette indices along with their RGB555
     * {@link #shrink(int) shrunken} analogues, so that {@link #sort16(int[])} can sort them. Mostly for internal use.
     */
    protected transient final int[] candidates = new int[32];

    /**
     * If this PaletteReducer has already calculated a palette, you can use this to save the slightly-slow-to-compute
     * palette mapping in a preload file for later runs. Once you have the file and the same int array originally used
     * for the RGBA8888 colors (e.g. {@code intColors}), you can load it when constructing a
     * PaletteReducer with {@code new PaletteReducer(intColors, PaletteReducer.loadPreloadFile(theFile))}.
     * @param file a writable non-null FileHandle; this will overwrite a file already present if it has the same name
     */
    public void writePreloadFile(FileHandle file){
        file.writeBytes(paletteMapping, false);
    }

    /**
     * If you saved a preload file with {@link #writePreloadFile(FileHandle)}, you can load it and give it to a
     * constructor with: {@code new PaletteReducer(intColors, PaletteReducer.loadPreloadFile(theFile))}, where intColors
     * is the original int array of RGBA8888 colors and theFile is the preload file written previously.
     * @param file a readable non-null FileHandle that should have been written by
     *             {@link #writePreloadFile(FileHandle)}, or otherwise contain the bytes of {@link #paletteMapping}
     * @return a byte array that should have a length of exactly 32768, to be passed to {@link #PaletteReducer(int[], byte[])}
     */
    public static byte[] loadPreloadFile(FileHandle file) {
        return file.readBytes();
    }
    
    /**
     * Constructs a default PaletteReducer that uses the "Snuggly" 255-color-plus-transparent palette.
     * Note that this uses a more-detailed and higher-quality metric than you would get by just specifying
     * {@code new PaletteReducer(PaletteReducer.SNUGGLY)}; this metric would be too slow to calculate at
     * runtime, but as pre-calculated data it works very well.
     */
    public PaletteReducer() {
        exact(SNUGGLY, ENCODED_SNUGGLY);
    }

    /**
     * Constructs a PaletteReducer that uses the given array of RGBA8888 ints as a palette (see {@link #exact(int[])}
     * for more info).
     *
     * @param rgbaPalette an array of RGBA8888 ints to use as a palette
     */
    public PaletteReducer(int[] rgbaPalette) {
        if(rgbaPalette == null)
        {
            exact(SNUGGLY, ENCODED_SNUGGLY);
            return;
        }
        exact(rgbaPalette);
    }
    /**
     * Constructs a PaletteReducer that uses the given array of RGBA8888 ints as a palette (see
     * {@link #exact(int[], int)} for more info).
     *
     * @param rgbaPalette an array of RGBA8888 ints to use as a palette
     * @param limit how many int items to use from rgbaPalette (this always starts at index 0)
     */
    public PaletteReducer(int[] rgbaPalette, int limit) {
        if(rgbaPalette == null)
        {
            exact(SNUGGLY, ENCODED_SNUGGLY);
            return;
        }
        exact(rgbaPalette, limit);
    }

    /**
     * Constructs a PaletteReducer that uses the given array of Color objects as a palette (see {@link #exact(Color[])}
     * for more info).
     *
     * @param colorPalette an array of Color objects to use as a palette
     */
    public PaletteReducer(Color[] colorPalette) {
        if(colorPalette == null)
        {
            exact(SNUGGLY, ENCODED_SNUGGLY);
            return;
        }
        exact(colorPalette);
    }

    /**
     * Constructs a PaletteReducer that uses the given array of Color objects as a palette (see
     * {@link #exact(Color[], int)} for more info).
     *
     * @param colorPalette an array of Color objects to use as a palette
     */
    public PaletteReducer(Color[] colorPalette, int limit) {
        if(colorPalette == null)
        {
            exact(SNUGGLY, ENCODED_SNUGGLY);
            return;
        }
        exact(colorPalette, limit);
    }

    /**
     * Constructs a PaletteReducer that analyzes the given Pixmap for color count and frequency to generate a palette
     * (see {@link #analyze(Pixmap)} for more info).
     *
     * @param pixmap a Pixmap to analyze in detail to produce a palette
     */
    public PaletteReducer(Pixmap pixmap) {
        if(pixmap == null)
        {
            exact(SNUGGLY, ENCODED_SNUGGLY);
            return;
        }
        analyze(pixmap);
    }

    /**
     * Constructs a PaletteReducer that analyzes the given Pixmaps for color count and frequency to generate a palette
     * (see {@link #analyze(Array)} for more info).
     *
     * @param pixmaps an Array of Pixmap to analyze in detail to produce a palette
     */
    public PaletteReducer(Array<Pixmap> pixmaps) {
        if(pixmaps == null)
        {
            exact(SNUGGLY, ENCODED_SNUGGLY);
            return;
        }
        analyze(pixmaps);
    }
    /**
     * Constructs a PaletteReducer that uses the given array of RGBA8888 ints as a palette (see
     * {@link #exact(int[], byte[])} for more info) and an encoded byte array to use to look up pre-loaded color data.
     * You can use {@link #writePreloadFile(FileHandle)} to write the preload data for a given PaletteReducer, and
     * {@link #loadPreloadFile(FileHandle)} to get a byte array of preload data from a previously-written file.
     * @param palette an array of RGBA8888 ints to use as a palette
     * @param preload a byte array containing preload data
     */
    public PaletteReducer(int[] palette, byte[] preload)
    {
        exact(palette, preload);
    }
    /**
     * Constructs a PaletteReducer that analyzes the given Pixmap for color count and frequency to generate a palette
     * (see {@link #analyze(Pixmap, double)} for more info).
     *
     * @param pixmap    a Pixmap to analyze in detail to produce a palette
     * @param threshold the minimum difference between colors required to put them in the palette (default 300)
     */
    public PaletteReducer(Pixmap pixmap, double threshold) {
        analyze(pixmap, threshold);
    }

//        return (RGB_POWERS[Math.abs(r1 - r2)]
//                + RGB_POWERS[256+Math.abs(g1 - g2)]
//                + RGB_POWERS[512+Math.abs(b1 - b2)]) * 0x1p-10;

//    public static double difference(int color1, int color2) {
//        if (((color1 ^ color2) & 0x80) == 0x80) return Double.POSITIVE_INFINITY;
//        final int indexA = (color1 >>> 17 & 0x7C00) | (color1 >>> 14 & 0x3E0) | (color1 >>> 11 & 0x1F),
//                indexB = (color2 >>> 17 & 0x7C00) | (color2 >>> 14 & 0x3E0) | (color2 >>> 11 & 0x1F);
//        float
//                L = OKLAB[0][indexA] - OKLAB[0][indexB],
//                A = OKLAB[1][indexA] - OKLAB[1][indexB],
//                B = OKLAB[2][indexA] - OKLAB[2][indexB];
//        L *= L;
//        A *= A;
//        B *= B;
//        return (L * L + A * A + B * B) * 0x1p+27;
//    }
//    public static double difference(int color1, int r2, int g2, int b2) {
//        if ((color1 & 0x80) == 0) return Double.POSITIVE_INFINITY;
//        final int indexA = (color1 >>> 17 & 0x7C00) | (color1 >>> 14 & 0x3E0) | (color1 >>> 11 & 0x1F),
//                indexB = (r2 << 7 & 0x7C00) | (g2 << 2 & 0x3E0) | (b2 >>> 3);
//        float
//                L = OKLAB[0][indexA] - OKLAB[0][indexB],
//                A = OKLAB[1][indexA] - OKLAB[1][indexB],
//                B = OKLAB[2][indexA] - OKLAB[2][indexB];
//        L *= L;
//        A *= A;
//        B *= B;
//        return (L * L + A * A + B * B) * 0x1p+27;
//    }
//    public static double difference(int r1, int g1, int b1, int r2, int g2, int b2) {
//        int indexA = (r1 << 7 & 0x7C00) | (g1 << 2 & 0x3E0) | (b1 >>> 3),
//                indexB = (r2 << 7 & 0x7C00) | (g2 << 2 & 0x3E0) | (b2 >>> 3);
//        float
//                L = OKLAB[0][indexA] - OKLAB[0][indexB],
//                A = OKLAB[1][indexA] - OKLAB[1][indexB],
//                B = OKLAB[2][indexA] - OKLAB[2][indexB];
//        L *= L;
//        A *= A;
//        B *= B;
//        return (L * L + A * A + B * B) * 0x1p+27;
//    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * If you want to change this, just change {@link #differenceMatch(int, int, int, int, int, int)}, which this
     * calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param color2 the second color, as an RGBA8888 int
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceMatch(int color1, int color2) {
        if (((color1 ^ color2) & 0x80) == 0x80) return Double.MAX_VALUE;
        return differenceMatch(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, color2 >>> 24, color2 >>> 16 & 0xFF, color2 >>> 8 & 0xFF);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * If you want to change this, just change {@link #differenceAnalyzing(int, int, int, int, int, int)}, which this
     * calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param color2 the second color, as an RGBA8888 int
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceAnalyzing(int color1, int color2) {
        if (((color1 ^ color2) & 0x80) == 0x80) return Double.MAX_VALUE;
        return differenceAnalyzing(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, color2 >>> 24, color2 >>> 16 & 0xFF, color2 >>> 8 & 0xFF);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * If you want to change this, just change {@link #differenceHW(int, int, int, int, int, int)}, which this calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param color2 the second color, as an RGBA8888 int
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceHW(int color1, int color2) {
        if (((color1 ^ color2) & 0x80) == 0x80) return Double.MAX_VALUE;
        return differenceHW(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, color2 >>> 24, color2 >>> 16 & 0xFF, color2 >>> 8 & 0xFF);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * If you want to change this, just change {@link #differenceMatch(int, int, int, int, int, int)}, which this calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceMatch(int color1, int r2, int g2, int b2) {
        if((color1 & 0x80) == 0) return Double.MAX_VALUE;
        return differenceMatch(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, r2, g2, b2);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25. If
     * you want to change this, just change {@link #differenceAnalyzing(int, int, int, int, int, int)}, which this
     * calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceAnalyzing(int color1, int r2, int g2, int b2) {
        if((color1 & 0x80) == 0) return Double.MAX_VALUE;
        return differenceAnalyzing(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, r2, g2, b2);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25. If
     * you want to change this, just change {@link #differenceHW(int, int, int, int, int, int)}, which this calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceHW(int color1, int r2, int g2, int b2) {
        if((color1 & 0x80) == 0) return Double.MAX_VALUE;
        return differenceHW(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, r2, g2, b2);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * This can be changed in an extending (possibly anonymous) class to use a different squared metric. This is used
     * when matching to an existing palette, as with {@link #exact(int[])}.
     * <br>
     * This uses Euclidean distance between the RGB colors in the 256-edge-length color cube. This does absolutely
     * nothing fancy with the colors, but this approach does well often. The same code is used by
     * {@link #differenceMatch(int, int, int, int, int, int)},
     * {@link #differenceAnalyzing(int, int, int, int, int, int)}, and
     * {@link #differenceHW(int, int, int, int, int, int)}, but classes can (potentially anonymously) subclass
     * PaletteReducer to change one, some, or all of these methods. The other difference methods call the 6-argument
     * overloads, so the override only needs to affect one method.
     *
     * @param r1 red of the first color, from 0 to 255
     * @param g1 green of the first color, from 0 to 255
     * @param b1 blue of the first color, from 0 to 255
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceMatch(int r1, int g1, int b1, int r2, int g2, int b2) {
        final int idx1 = ((r1 << 7) & 0x7C00) | ((g1 << 2) & 0x3E0) | ((b1 >>> 3));
        final int idx2 = ((r2 << 7) & 0x7C00) | ((g2 << 2) & 0x3E0) | ((b2 >>> 3));
        final double dL = (OKLAB[0][idx1] - OKLAB[0][idx2]) * 512.0;
        final double dA = (OKLAB[1][idx1] - OKLAB[1][idx2]) * 512.0;
        final double dB = (OKLAB[2][idx1] - OKLAB[2][idx2]) * 512.0;
        return (dL * dL + dA * dA + dB * dB);

//        double rf = (EXACT_LOOKUP[r1] - EXACT_LOOKUP[r2]) * 1.55;// rf *= rf;// * 0.875;
//        double gf = (EXACT_LOOKUP[g1] - EXACT_LOOKUP[g2]) * 2.05;// gf *= gf;// * 0.75;
//        double bf = (EXACT_LOOKUP[b1] - EXACT_LOOKUP[b2]) * 0.90;// bf *= bf;// * 1.375;
//
//        return  (rf * rf + gf * gf + bf * bf) * 0x1.8p17;
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * This can be changed in an extending (possibly anonymous) class to use a different squared metric. This is used
     * when analyzing an image, as with {@link #analyze(Pixmap)}.
     * <br>
     * This uses Euclidean distance between the RGB colors in the 256-edge-length color cube. This does absolutely
     * nothing fancy with the colors, but this approach does well often. The same code is used by
     * {@link #differenceMatch(int, int, int, int, int, int)},
     * {@link #differenceAnalyzing(int, int, int, int, int, int)}, and
     * {@link #differenceHW(int, int, int, int, int, int)}, but classes can (potentially anonymously) subclass
     * PaletteReducer to change one, some, or all of these methods. The other difference methods call the 6-argument
     * overloads, so the override only needs to affect one method.
     *
     * @param r1 red of the first color, from 0 to 255
     * @param g1 green of the first color, from 0 to 255
     * @param b1 blue of the first color, from 0 to 255
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceAnalyzing(int r1, int g1, int b1, int r2, int g2, int b2) {
        final int idx1 = ((r1 << 7) & 0x7C00) | ((g1 << 2) & 0x3E0) | ((b1 >>> 3));
        final int idx2 = ((r2 << 7) & 0x7C00) | ((g2 << 2) & 0x3E0) | ((b2 >>> 3));
        final double dL = (OKLAB[0][idx1] - OKLAB[0][idx2]) * 512.0;
        final double dA = (OKLAB[1][idx1] - OKLAB[1][idx2]) * 512.0;
        final double dB = (OKLAB[2][idx1] - OKLAB[2][idx2]) * 512.0;
        return (dL * dL + dA * dA + dB * dB);

//        int rf = (r1 - r2);
//        int gf = (g1 - g2);
//        int bf = (b1 - b2);
//        return (rf * rf + gf * gf + bf * bf);

//        double rf = (ANALYTIC_LOOKUP[r1] - ANALYTIC_LOOKUP[r2]);
//        double gf = (ANALYTIC_LOOKUP[g1] - ANALYTIC_LOOKUP[g2]);
//        double bf = (ANALYTIC_LOOKUP[b1] - ANALYTIC_LOOKUP[b2]);
//
//        return (rf * rf + gf * gf + bf * bf) * 0x1.4p17;
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * This can be changed in an extending (possibly anonymous) class to use a different squared metric. This is used
     * when analyzing an image with {@link #analyzeHueWise(Pixmap, double, int)} .
     * <br>
     * This uses Euclidean distance between the RGB colors in the 256-edge-length color cube. This does absolutely
     * nothing fancy with the colors, but this approach does well often. The same code is used by
     * {@link #differenceMatch(int, int, int, int, int, int)},
     * {@link #differenceAnalyzing(int, int, int, int, int, int)}, and
     * {@link #differenceHW(int, int, int, int, int, int)}, but classes can (potentially anonymously) subclass
     * PaletteReducer to change one, some, or all of these methods. The other difference methods call the 6-argument
     * overloads, so the override only needs to affect one method.
     *
     * @param r1 red of the first color, from 0 to 255
     * @param g1 green of the first color, from 0 to 255
     * @param b1 blue of the first color, from 0 to 255
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance, between colors 1 and 2
     */
    public double differenceHW(int r1, int g1, int b1, int r2, int g2, int b2) {
        final int idx1 = ((r1 << 7) & 0x7C00) | ((g1 << 2) & 0x3E0) | ((b1 >>> 3));
        final int idx2 = ((r2 << 7) & 0x7C00) | ((g2 << 2) & 0x3E0) | ((b2 >>> 3));
        final double dL = (OKLAB[0][idx1] - OKLAB[0][idx2]) * 512.0;
        final double dA = (OKLAB[1][idx1] - OKLAB[1][idx2]) * 512.0;
        final double dB = (OKLAB[2][idx1] - OKLAB[2][idx2]) * 512.0;
        return (dL * dL + dA * dA + dB * dB);

//        int rf = (r1 - r2);
//        int gf = (g1 - g2);
//        int bf = (b1 - b2);
//        return (rf * rf + gf * gf + bf * bf);

//        double rf = (ANALYTIC_LOOKUP[r1] - ANALYTIC_LOOKUP[r2]);
//        double gf = (ANALYTIC_LOOKUP[g1] - ANALYTIC_LOOKUP[g2]);
//        double bf = (ANALYTIC_LOOKUP[b1] - ANALYTIC_LOOKUP[b2]);

//        return (rf * rf + gf * gf + bf * bf) * 0x1.4p17;
    }

    /**
     * Resets the palette to the 256-color (including transparent) "Snuggly" palette. PaletteReducer already
     * stores most of the calculated data needed to use this one palette. Note that this uses a more-detailed
     * and higher-quality metric than you would get by just specifying
     * {@code new PaletteReducer(PaletteReducer.SNUGGLY)}; this metric would be too slow to calculate at
     * runtime, but as pre-calculated data it works very well.
     */
    public void setDefaultPalette(){
        exact(SNUGGLY, ENCODED_SNUGGLY);
    }
    /**
     * Builds the palette information this PNG8 stores from the RGBA8888 ints in {@code rgbaPalette}, up to 256 colors.
     * Alpha is not preserved except for the first item in rgbaPalette, and only if it is {@code 0} (fully transparent
     * black); otherwise all items are treated as opaque. If rgbaPalette is null, empty, or only has one color, then
     * this defaults to the "Snuggly" palette with 256 well-distributed colors (including transparent).
     *
     * @param rgbaPalette an array of RGBA8888 ints; all will be used up to 256 items or the length of the array
     */
    public void exact(int[] rgbaPalette) {
        exact(rgbaPalette, 256);
    }
    /**
     * Builds the palette information this PNG8 stores from the RGBA8888 ints in {@code rgbaPalette}, up to 256 colors
     * or {@code limit}, whichever is less.
     * Alpha is not preserved except for the first item in rgbaPalette, and only if it is {@code 0} (fully transparent
     * black); otherwise all items are treated as opaque. If rgbaPalette is null, empty, or only has one color, or if
     * limit is less than 2, then this defaults to the "Snuggly" palette with 256 well-distributed colors (including
     * transparent).
     *
     * @param rgbaPalette an array of RGBA8888 ints; all will be used up to 256 items or the length of the array
     * @param limit       a limit on how many int items to use from rgbaPalette; useful if rgbaPalette is from an IntArray
     */
    public void exact(int[] rgbaPalette, int limit) {
        if (rgbaPalette == null || rgbaPalette.length < 2 || limit < 2) {
            exact(SNUGGLY, ENCODED_SNUGGLY);
            return;
        }
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        final int plen = Math.min(Math.min(256, limit), rgbaPalette.length);
        colorCount = plen;
        populationBias = (float) Math.exp(-1.375/colorCount);
        int color, c2;
        double dist;
        for (int i = 0; i < plen; i++) {
            color = rgbaPalette[i];
            if ((color & 0x80) != 0) {
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
            }
        }
        int rr, gg, bb;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = 1E100;
                        for (int i = 1; i < plen; i++) {
                            if (dist > (dist = Math.min(dist, differenceMatch(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
   }

    /**
     * Builds the palette information this PaletteReducer stores from the given array of RGBA8888 ints as a palette (see
     * {@link #exact(int[])} for more info) and an encoded byte array to use to look up pre-loaded color data. The
     * encoded byte array can be copied out of the {@link #paletteMapping} of an existing PaletteReducer. There's
     * slightly more startup time spent when initially calling {@link #exact(int[])}, but it will produce the same
     * result. You can store the paletteMapping from that PaletteReducer once, however you want to store it, and send it
     * back to this on later runs.
     *
     * @param palette an array of RGBA8888 ints to use as a palette
     * @param preload a byte array with exactly 32768 (or 0x8000) items, containing {@link #paletteMapping} data
     */
    public void exact(int[] palette, byte[] preload)
    {
        if(palette == null || preload == null)
        {
            System.arraycopy(SNUGGLY, 0,  paletteArray, 0, 256);
            System.arraycopy(ENCODED_SNUGGLY, 0,  paletteMapping, 0, 0x8000);
            colorCount = 256;
            populationBias = (float) Math.exp(-1.125 / 256.0);
            return;
        }
        long startTime = System.currentTimeMillis();
        colorCount = Math.min(256, palette.length);
        System.arraycopy(palette, 0,  paletteArray, 0, colorCount);
        System.arraycopy(preload, 0,  paletteMapping, 0, 0x8000);
        populationBias = (float) Math.exp(-1.375/colorCount);
    }

    /**
     * Builds the palette information this PaletteReducer stores from the Color objects in {@code colorPalette}, up to
     * 256 colors.
     * Alpha is not preserved except for the first item in colorPalette, and only if its r, g, b, and a values are all
     * 0f (fully transparent black); otherwise all items are treated as opaque. If rgbaPalette is null, empty, or only
     * has one color, then this defaults to the "Snuggly" palette with 256 well-distributed colors (including
     * transparent).
     *
     * @param colorPalette an array of Color objects; all will be used up to 256 items or the length of the array
     */
    public void exact(Color[] colorPalette) {
        exact(colorPalette, 256);
    }

    /**
     * Builds the palette information this PaletteReducer stores from the Color objects in {@code colorPalette}, up to
     * 256 colors or {@code limit}, whichever is less.
     * Alpha is not preserved except for the first item in colorPalette, and only if its r, g, b, and a values are all
     * 0f (fully transparent black); otherwise all items are treated as opaque. If rgbaPalette is null, empty, only has
     * one color, or limit is less than 2, then this defaults to the "Snuggly" palette with 256 well-distributed
     * colors (including transparent).
     *
     * @param colorPalette an array of Color objects; all will be used up to 256 items, limit, or the length of the array
     * @param limit        a limit on how many Color items to use from colorPalette; useful if colorPalette is from an Array
     */
    public void exact(Color[] colorPalette, int limit) {
        if (colorPalette == null || colorPalette.length < 2 || limit < 2) {
            exact(SNUGGLY, ENCODED_SNUGGLY);
            return;
        }
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        final int plen = Math.min(Math.min(256, colorPalette.length), limit);
        colorCount = plen;
        populationBias = (float) Math.exp(-1.375/colorCount);
        int color, c2;
        double dist;

        for (int i = 0; i < plen; i++) {
            color = Color.rgba8888(colorPalette[i]);
            paletteArray[i] = color;
            paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
        }
        int rr, gg, bb;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = 0x7FFFFFFF;
                        for (int i = 1; i < plen; i++) {
                            if (dist > (dist = Math.min(dist, differenceMatch(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
    }

    protected static final Comparator<IntIntMap.Entry> entryComparator = new Comparator<IntIntMap.Entry>() {
        @Override
        public int compare(IntIntMap.Entry o1, IntIntMap.Entry o2) {
            return o2.value - o1.value;
        }
    };

//    private static final Comparator<IntFloatMap.Entry> intFloatEntryComparator = new Comparator<IntFloatMap.Entry>() {
//        @Override
//        public int compare(IntFloatMap.Entry o1, IntFloatMap.Entry o2) {
//            return NumberUtils.floatToIntBits(o2.value - o1.value);
//        }
//    };

    /**
     * Just like Comparator, but compares primitive ints.
     */
    public interface IntComparator {
        /**
         * Compares its two primitive-type arguments for order. Returns a negative
         * integer, zero, or a positive integer as the first argument is less than,
         * equal to, or greater than the second.
         *
         * @return a negative integer, zero, or a positive integer as the first argument
         * is less than, equal to, or greater than the second.
         * @see Comparator
         */
        int compare(int k1, int k2);
    }

    /**
     * Compares shrunken indices (RGB555) by lightness as Oklab knows it.
     */
    protected static final IntComparator lightnessComparator = new IntComparator() {
        @Override
        public int compare(int k1, int k2) {
            return NumberUtils.floatToIntBits(OKLAB[0][k2] - OKLAB[0][k1]);
        }
    };

    /**
     * Compares shrunken indices (RGB555) by hue as Oklab knows it.
     */
    protected static final IntComparator hueComparator = new IntComparator() {
        @Override
        public int compare(int k1, int k2) {
            return NumberUtils.floatToIntBits(OKLAB[3][k2] - OKLAB[3][k1]);
        }
    };

    /// Start of code primarily based on FastUtil.
    /// See https://github.com/vigna/fastutil/blob/8.5.8/LICENSE-2.0 for
    // the license of this block (Apache License 2.0).

    private static void swap (int[] items, int first, int second) {
        int firstValue = items[first];
        items[first] = items[second];
        items[second] = firstValue;
    }

    /**
     * Transforms two consecutive sorted ranges into a single sorted range. The initial ranges are
     * {@code [first..middle)} and {@code [middle..last)}, and the resulting range is
     * {@code [first..last)}. Elements in the first input range will precede equal elements in
     * the second.
     */
    private static void inPlaceMerge (int[] items, final int from, int mid, final int to, final IntComparator comp) {
        if (from >= mid || mid >= to) {return;}
        if (to - from == 2) {
            if (comp.compare(items[mid], items[from]) < 0) {swap(items, from, mid);}
            return;
        }

        int firstCut;
        int secondCut;

        if (mid - from > to - mid) {
            firstCut = from + (mid - from) / 2;
            secondCut = lowerBound(items, mid, to, firstCut, comp);
        } else {
            secondCut = mid + (to - mid) / 2;
            firstCut = upperBound(items, from, mid, secondCut, comp);
        }

        int first2 = firstCut;
        int middle2 = mid;
        int last2 = secondCut;
        if (middle2 != first2 && middle2 != last2) {
            int first1 = first2;
            int last1 = middle2;
            while (first1 < --last1) {swap(items, first1++, last1);}
            first1 = middle2;
            last1 = last2;
            while (first1 < --last1) {swap(items, first1++, last1);}
            first1 = first2;
            last1 = last2;
            while (first1 < --last1) {swap(items, first1++, last1);}
        }

        mid = firstCut + secondCut - mid;
        inPlaceMerge(items, from, firstCut, mid, comp);
        inPlaceMerge(items, mid, secondCut, to, comp);
    }

    /**
     * Performs a binary search on an already-sorted range: finds the first position where an
     * element can be inserted without violating the ordering. Sorting is by a user-supplied
     * comparison function.
     *
     * @param items the int array to be sorted
     * @param from  the index of the first element (inclusive) to be included in the binary search.
     * @param to    the index of the last element (exclusive) to be included in the binary search.
     * @param pos   the position of the element to be searched for.
     * @param comp  the comparison function.
     * @return the largest index i such that, for every j in the range {@code [first..i)},
     * {@code comp.compare(get(j), get(pos))} is {@code true}.
     */
    private static int lowerBound (int[] items, int from, final int to, final int pos, final IntComparator comp) {
        int len = to - from;
        while (len > 0) {
            int half = len / 2;
            int middle = from + half;
            if (comp.compare(items[middle], items[pos]) < 0) {
                from = middle + 1;
                len -= half + 1;
            } else {
                len = half;
            }
        }
        return from;
    }

    /**
     * Performs a binary search on an already sorted range: finds the last position where an element
     * can be inserted without violating the ordering. Sorting is by a user-supplied comparison
     * function.
     *
     * @param items the int array to be sorted
     * @param from  the index of the first element (inclusive) to be included in the binary search.
     * @param to    the index of the last element (exclusive) to be included in the binary search.
     * @param pos   the position of the element to be searched for.
     * @param comp  the comparison function.
     * @return The largest index i such that, for every j in the range {@code [first..i)},
     * {@code comp.compare(get(pos), get(j))} is {@code false}.
     */
    private static int upperBound (int[] items, int from, final int to, final int pos, final IntComparator comp) {
        int len = to - from;
        while (len > 0) {
            int half = len / 2;
            int middle = from + half;
            if (comp.compare(items[pos], items[middle]) < 0) {
                len = half;
            } else {
                from = middle + 1;
                len -= half + 1;
            }
        }
        return from;
    }

    /**
     * Sorts all of {@code items} by simply calling {@link #sort(int[], int, int, IntComparator)},
     * setting {@code from} and {@code to} so the whole array is sorted.
     *
     * @param items the int array to be sorted
     * @param c     a IntComparator to alter the sort order; if null, the natural order will be used
     */
    public static void sort (int[] items, final IntComparator c) {
        sort(items, 0, items.length, c);
    }

    /**
     * Sorts the specified range of elements according to the order induced by the specified
     * comparator using mergesort.
     *
     * <p>This sort is guaranteed to be <i>stable</i>: equal elements will not be reordered as a result
     * of the sort. The sorting algorithm is an in-place mergesort that is significantly slower than a
     * standard mergesort, as its running time is <i>O</i>(<var>n</var>&nbsp;(log&nbsp;<var>n</var>)<sup>2</sup>),
     * but it does not allocate additional memory; as a result, it can be
     * used as a generic sorting algorithm.
     *
     * <p>If and only if {@code c} is null, this will delegate to {@link Arrays#sort(int[], int, int)}, which
     * does not have the same guarantees regarding allocation.
     *
     * @param items the int array to be sorted
     * @param from  the index of the first element (inclusive) to be sorted.
     * @param to    the index of the last element (exclusive) to be sorted.
     * @param c     a IntComparator to alter the sort order; if null, the natural order will be used
     */
    public static void sort (int[] items, final int from, final int to, final IntComparator c) {
        if (to <= 0) {
            return;
        }
        if (from < 0 || from >= items.length || to > items.length) {
            throw new UnsupportedOperationException("The given from/to range in IntComparators.sort() is invalid.");
        }
        if (c == null) {
            Arrays.sort(items, from, to);
            return;
        }
        /*
         * We retain the same method signature as quickSort. Given only a comparator and this list
         * do not know how to copy and move elements from/to temporary arrays. Hence, in contrast to
         * the JDK mergesorts this is an "in-place" mergesort, i.e. does not allocate any temporary
         * arrays. A non-inplace mergesort would perhaps be faster in most cases, but would require
         * non-intuitive delegate objects...
         */
        final int length = to - from;

        // Insertion sort on smallest arrays, less than 16 items
        if (length < 16) {
            for (int i = from; i < to; i++) {
                for (int j = i; j > from && c.compare(items[j - 1], items[j]) > 0; j--) {
                    swap(items, j, j - 1);
                }
            }
            return;
        }

        // Recursively sort halves
        int mid = from + to >>> 1;
        sort(items, from, mid, c);
        sort(items, mid, to, c);

        // If list is already sorted, nothing left to do. This is an
        // optimization that results in faster sorts for nearly ordered lists.
        if (c.compare(items[mid - 1], items[mid]) <= 0) {return;}

        // Merge sorted halves
        inPlaceMerge(items, from, mid, to, c);
    }

    //// End of code primarily from FastUtil.

    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most 256 colors if there are
     * too many colors to store in a PNG-8 palette. If there are 256 or fewer colors, this uses the exact colors
     * (although with at most one transparent color, and no alpha for other colors); this will always reserve a palette
     * entry for transparent (even if the image has no transparency) because it uses palette index 0 in its analysis
     * step. Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that
     * aren't exact, and dithering works better when the palette can choose colors that are sufficiently different, this
     * uses a threshold value to determine whether it should permit a less-common color into the palette, and if the
     * second color is different enough (as measured by {@link #differenceAnalyzing(int, int)}) by a value of at least
     * 300, it is
     * allowed in the palette, otherwise it is kept out for being too similar to existing colors. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} field or can be used directly to {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmap a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     */
    public void analyze(Pixmap pixmap) {
        analyze(pixmap, 100);
    }

    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most 256 colors if there are
     * too many colors to store in a PNG-8 palette. If there are 256 or fewer colors, this uses the exact colors
     * (although with at most one transparent color, and no alpha for other colors); this will always reserve a palette
     * entry for transparent (even if the image has no transparency) because it uses palette index 0 in its analysis
     * step. Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that
     * aren't exact, and dithering works better when the palette can choose colors that are sufficiently different, this
     * takes a threshold value to determine whether it should permit a less-common color into the palette, and if the
     * second color is different enough (as measured by {@link #differenceAnalyzing(int, int)} ) by a value of at least
     * {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. The threshold is usually between 50 and 200, and 100 is a good default. Because this always uses the
     * maximum color limit, threshold should be lower than cases where the color limit is small. If the threshold is too
     * high, then some colors that would be useful to smooth out subtle color changes won't get considered, and colors
     * may change more abruptly. This doesn't return a value but instead stores the palette info in this object; a
     * PaletteReducer can be assigned to the {@link PNG8#palette} or {@link AnimatedGif#palette} fields or can be used
     * directly to {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)} ; usually between 50 and 200, 100 is a good default
     */
    public void analyze(Pixmap pixmap, double threshold) {
        analyze(pixmap, threshold, 256);
    }

    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most {@code limit} colors.
     * If there are {@code limit} or fewer colors, this uses the exact colors (although with at most one transparent
     * color, and no alpha for other colors); this will always reserve a palette entry for transparent (even if the
     * image has no transparency) because it uses palette index 0 in its analysis step. Because calling
     * {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that aren't exact, and dithering
     * works better when the palette can choose colors that are sufficiently different, this takes a threshold value to
     * determine whether it should permit a less-common color into the palette, and if the second color is different
     * enough (as measured by {@link #differenceAnalyzing(int, int)} ) by a value of at least {@code threshold}, it is allowed in
     * the palette, otherwise it is kept out for being too similar to existing colors. The threshold is usually between
     * 50 and 200, and 100 is a good default. If the threshold is too high, then some colors that would be useful to
     * smooth out subtle color changes won't get considered, and colors may change more abruptly. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to {@link #reduce(Pixmap)} a
     * Pixmap.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 200, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyze(Pixmap pixmap, double threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 2), 256);
        threshold /= Math.min(0.45, Math.pow(limit + 16, 1.45) * 0.0002);
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntIntMap counts = new IntIntMap(limit);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                color = pixmap.getPixel(x, y) & 0xF8F8F880;
                if ((color & 0x80) != 0) {
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    counts.getAndIncrement(color, 0, 1);
                }
            }
        }
        int cs = counts.size;
        Array<IntIntMap.Entry> es = new Array<>(cs);
        for(IntIntMap.Entry e : counts)
        {
            IntIntMap.Entry e2 = new IntIntMap.Entry();
            e2.key = e.key;
            e2.value = e.value;
            es.add(e2);
        }
        es.sort(entryComparator);
        if (cs < limit) {
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            while (i < limit && c < cs) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    if (differenceAnalyzing(color, paletteArray[j]) < threshold)
                        continue PER_BEST;
                }
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        }

        int c2;
        int rr, gg, bb;
        double dist;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = Double.MAX_VALUE;
                        for (int i = 1; i < colorCount; i++) {
                            if (dist > (dist = Math.min(dist, differenceAnalyzing(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
    }

    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most {@code limit} colors.
     * If there are {@code limit} or fewer colors, this uses the exact colors (although with at most one transparent
     * color, and no alpha for other colors); this will always reserve a palette entry for transparent (even if the
     * image has no transparency) because it uses palette index 0 in its analysis step. Because calling
     * {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that aren't exact, and dithering
     * works better when the palette can choose colors that are sufficiently different, this takes a threshold value to
     * determine whether it should permit a less-common color into the palette, and if the second color is different
     * enough (as measured by {@link #differenceHW(int, int)} ) by a value of at least {@code threshold}, it is allowed in
     * the palette, otherwise it is kept out for being too similar to existing colors. The threshold is usually between
     * 50 and 200, and 100 is a good default. If the threshold is too high, then some colors that would be useful to
     * smooth out subtle color changes won't get considered, and colors may change more abruptly. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to {@link #reduce(Pixmap)} a
     * Pixmap.
     * <br>
     * The algorithm here isn't incredibly fast, but is often better at preserving colors that are used often enough to
     * be important to an image, but not often enough to appear in a small palette produced by {@link #analyze(Pixmap)}.
     * It involves sorting about 10% of the pixels in the image by hue, dividing up those pixels into evenly-sized
     * ranges, then sorting those ranges individually by lightness and dividing those into sub-ranges. The sub-ranges
     * have their chroma channels averaged (these already have similar hue, so this mostly affects saturation), and
     * their lightness averaged but pushed towards more extreme values using
     * {@link OtherMath#barronSpline(float, float, float)}. This last step works well with dithering.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by PNG8, or by AnimatedGif
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 200, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyzeHueWise(Pixmap pixmap, double threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 3), 256);
        threshold /= Math.pow(limit, 1.35) * 0.000043;
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntIntMap counts = new IntIntMap(limit);
        IntArray enc = new IntArray(width * height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                color = pixmap.getPixel(x, y) & 0xF8F8F880;
                if ((color & 0x80) != 0) {
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    counts.getAndIncrement(color, 0, 1);
                    if(((x & y) * 5 & 31) < 3)
                        enc.add(shrink(color));
                }
            }
        }
        int cs = counts.size;
        if (cs < limit) {
            Array<IntIntMap.Entry> es = new Array<>(cs);
            for(IntIntMap.Entry e : counts)
            {
                IntIntMap.Entry e2 = new IntIntMap.Entry();
                e2.key = e.key;
                e2.value = e.value;
                es.add(e2);
            }
            es.sort(entryComparator);
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        } else // generate colors
        {
            final int[] ei = enc.items;
            sort(ei, 0, enc.size, hueComparator);
            paletteArray[1] = -1; // white
            paletteArray[2] = 255; // black
            int i = 3, encs = enc.size, segments = Math.min(encs, limit - 3) + 1 >> 1, e = 0;
            double lightPieces = Math.ceil(Math.log(limit));
            PER_BEST:
            for (int s = 0; i < limit; s++) {
                if(e > (e %= encs)){
                    segments++;
                    lightPieces++;
                    threshold *= 0.9;
                }
                s %= segments;
                int segStart = e, segEnd = Math.min(segStart + (int)Math.ceil(encs / (double)segments), encs), segLen = segEnd - segStart;
                sort(ei, segStart, segLen, lightnessComparator);
                for (int li = 0; li < lightPieces && li < segLen && i < limit; li++) {
                    int start = e, end = Math.min(encs, start + (int)Math.ceil(segLen / lightPieces)), len = end - start;

                    float totalL = 0.0f, totalA = 0.0f, totalB = 0.0f;
                    for (; e < end; e++) {
                        int index = ei[e];
                        totalL += OKLAB[0][index];
                        totalA += OKLAB[1][index];
                        totalB += OKLAB[2][index];
                    }
                    totalA /= len;
                    totalB /= len;
                    color = oklabToRGB(
                            OtherMath.barronSpline(totalL / len, 3f, 0.5f),
                            totalA,//(OtherMath.cbrt(totalA) + 31f * totalA) * 0x1p-5f,
                            totalB,//(OtherMath.cbrt(totalB) + 31f * totalB) * 0x1p-5f,
                            1f);
//                    (OtherMath.barronSpline(totalA / (len<<1)+0.5f, 2f, 0.5f)-0.5f)*2f,
//                    (OtherMath.barronSpline(totalB / (len<<1)+0.5f, 2f, 0.5f)-0.5f)*2f,

                    for (int j = 3; j < i; j++) {
                        if (differenceHW(color, paletteArray[j]) < threshold)
                            continue PER_BEST;
                    }
                    paletteArray[i] = color;
                    paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                    i++;
                }
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        }

        int c2;
        int rr, gg, bb;
        double dist;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = Double.MAX_VALUE;
                        for (int i = 1; i < colorCount; i++) {
                            if (dist > (dist = Math.min(dist, differenceHW(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
    }

    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most {@code limit} colors.
     * If there are {@code limit} or fewer colors, this uses the exact colors (although with at most one transparent
     * color, and no alpha for other colors); if there are more than {@code limit} colors or any colors have 50% or less
     * alpha, it will reserve a palette entry for transparent (even if the image has no transparency). Because calling
     * {@link #reduce(Pixmap)} (or any of PNG8's or AnimatedGif's write methods) will dither colors that aren't exact,
     * and dithering works better when the palette can choose colors that are sufficiently different, this takes a
     * threshold value to determine whether it should permit a less-common color into the palette. If the second color
     * is different enough (as measured by {@link #differenceAnalyzing(int, int)} ) by a value of at least
     * {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. The threshold is usually between 50 and 200, and 100 is a good default.
     * If the threshold is too high, then some colors that would be useful to smooth out subtle color changes won't get
     * considered, and colors may change more abruptly. If the threshold is too low, many similar colors may be chosen
     * at the expense of some less common, but still important, colors.
     * This doesn't return a value but instead stores the palette info in this object; a PaletteReducer can be assigned
     * to the {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     * <br>
     * This does a faster and less accurate analysis, and is more suitable to do on each frame of a large animation when
     * time is better spent making more images than fewer images at higher quality. It should be about 5 times faster
     * than {@link #analyze(Pixmap, double, int)} with the same parameters.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 200, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyzeFast(Pixmap pixmap, double threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 2), 256);
        threshold /= Math.min(0.45, Math.pow(limit + 16, 1.45) * 0.0002);
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntIntMap counts = new IntIntMap(limit);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                color = pixmap.getPixel(x, y) & 0xF8F8F880;
                if ((color & 0x80) != 0) {
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    counts.getAndIncrement(color, 0, 1);
                }
            }
        }
        int cs = counts.size;
        Array<IntIntMap.Entry> es = new Array<>(cs);
        for(IntIntMap.Entry e : counts)
        {
            IntIntMap.Entry e2 = new IntIntMap.Entry();
            e2.key = e.key;
            e2.value = e.value;
            es.add(e2);
        }
        es.sort(entryComparator);
        if (cs < limit) {
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                ++i;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            while (i < limit && c < cs) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    if (differenceAnalyzing(color, paletteArray[j]) < threshold)
                        continue PER_BEST;
                }
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        }

        if(colorCount <= 1)
            return;
        int c2;
        byte bt;
        int numUnassigned = 1, iterations = 0;
        byte[] buffer = Arrays.copyOf(paletteMapping, 0x8000);
        while (numUnassigned != 0) {
            numUnassigned = 0;
            for (int r = 0; r < 32; r++) {
                for (int g = 0; g < 32; g++) {
                    for (int b = 0; b < 32; b++) {
                        c2 = r << 10 | g << 5 | b;
                        if (buffer[c2] == 0) {
                            if(iterations++ != 2){
                                if (b < 31 && (bt = paletteMapping[c2 + 1]) != 0)
                                    buffer[c2] = bt;
                                else if (g < 31 && (bt = paletteMapping[c2 + 32]) != 0)
                                    buffer[c2] = bt;
                                else if (r < 31 && (bt = paletteMapping[c2 + 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (b > 0 && (bt = paletteMapping[c2 - 1]) != 0)
                                    buffer[c2] = bt;
                                else if (g > 0 && (bt = paletteMapping[c2 - 32]) != 0)
                                    buffer[c2] = bt;
                                else if (r > 0 && (bt = paletteMapping[c2 - 1024]) != 0)
                                    buffer[c2] = bt;
                                else numUnassigned++;
                            }
                            else {
                                iterations = 0;
                                if (b < 31 && (bt = paletteMapping[c2 + 1]) != 0)
                                    buffer[c2] = bt;
                                else if (g < 31 && (bt = paletteMapping[c2 + 32]) != 0)
                                    buffer[c2] = bt;
                                else if (r < 31 && (bt = paletteMapping[c2 + 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (b > 0 && (bt = paletteMapping[c2 - 1]) != 0)
                                    buffer[c2] = bt;
                                else if (g > 0 && (bt = paletteMapping[c2 - 32]) != 0)
                                    buffer[c2] = bt;
                                else if (r > 0 && (bt = paletteMapping[c2 - 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (b < 31 && g < 31 && (bt = paletteMapping[c2 + 1 + 32]) != 0)
                                    buffer[c2] = bt;
                                else if (b < 31 && r < 31 && (bt = paletteMapping[c2 + 1 + 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (g < 31 && r < 31 && (bt = paletteMapping[c2 + 32 + 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (b > 0 && g > 0 && (bt = paletteMapping[c2 - 1 - 32]) != 0)
                                    buffer[c2] = bt;
                                else if (b > 0 && r > 0 && (bt = paletteMapping[c2 - 1 - 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (g > 0 && r > 0 && (bt = paletteMapping[c2 - 32 - 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (b < 31 && g > 0 && (bt = paletteMapping[c2 + 1 - 32]) != 0)
                                    buffer[c2] = bt;
                                else if (b < 31 && r > 0 && (bt = paletteMapping[c2 + 1 - 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (g < 31 && r > 0 && (bt = paletteMapping[c2 + 32 - 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (b > 0 && g < 31 && (bt = paletteMapping[c2 - 1 + 32]) != 0)
                                    buffer[c2] = bt;
                                else if (b > 0 && r < 31 && (bt = paletteMapping[c2 - 1 + 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (g > 0 && r < 31 && (bt = paletteMapping[c2 - 32 + 1024]) != 0)
                                    buffer[c2] = bt;
                                else numUnassigned++;

                            }
                        }
                    }
                }
            }
            System.arraycopy(buffer, 0, paletteMapping, 0, 0x8000);
        }
    }

    public void analyzeMC(Pixmap pixmap, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntArray bin = new IntArray(width * height);
        IntIntMap counts = new IntIntMap(limit);
        int hasTransparent = 0;
        int rangeR, rangeG, rangeB;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                color = pixmap.getPixel(x, y) & 0xF8F8F880;
                if ((color & 0x80) != 0) {
                    bin.add(color |= (color >>> 5 & 0x07070700) | 0xFF);
                    counts.getAndIncrement(color, 0, 1);
                } else {
                    hasTransparent = 1;
                }
            }
        }
        limit = Math.max(2 - hasTransparent, Math.min(limit - hasTransparent, 256));
        if(counts.size > limit) {
            int numCuts = 32 - Integer.numberOfLeadingZeros(limit - 1);
            int offset, end = bin.size;
            int[] in = bin.items, out = new int[end],
                    bufR = new int[32],
                    bufG = new int[32],
                    bufB = new int[32];
            for (int stage = 0; stage < numCuts; stage++) {
                int size = bin.size >>> stage;
                offset = 0;
                end = 0;
                for (int part = 1 << stage; part > 0; part--) {
                    if (part == 1)
                        end = bin.size;
                    else
                        end += size;
                    Arrays.fill(bufR, 0);
                    Arrays.fill(bufG, 0);
                    Arrays.fill(bufB, 0);
                    for (int i = offset, ii; i < end; i++) {
                        ii = in[i];
                        bufR[ii >>> 27]++;
                        bufG[ii >>> 19 & 31]++;
                        bufB[ii >>> 11 & 31]++;
                    }
                    for (rangeR = 32; rangeR > 0 && bufR[rangeR - 1] == 0; rangeR--) ;
                    for (int r = 0; r < rangeR && bufR[r] == 0; r++, rangeR--) ;
                    for (rangeG = 32; rangeG > 0 && bufG[rangeG - 1] == 0; rangeG--) ;
                    for (int r = 0; r < rangeG && bufG[r] == 0; r++, rangeG--) ;
                    for (rangeB = 32; rangeB > 0 && bufB[rangeB - 1] == 0; rangeB--) ;
                    for (int r = 0; r < rangeB && bufB[r] == 0; r++, rangeB--) ;

                    if (rangeG >= rangeR && rangeG >= rangeB)
                    {
                        for (int i = 1; i < 32; i++)
                            bufG[i] += bufG[i - 1];
                        for (int i = end - 1; i >= offset; i--)
                            out[offset + --bufG[in[i] >>> 19 & 31]] = in[i];
                    }
                    else if (rangeR >= rangeG && rangeR >= rangeB)
                    {
                        for (int i = 1; i < 32; i++)
                            bufR[i] += bufR[i - 1];
                        for (int i = end - 1; i >= offset; i--)
                            out[offset + --bufR[in[i] >>> 27]] = in[i];
                    }
                    else
                    {
                        for (int i = 1; i < 32; i++)
                            bufB[i] += bufB[i - 1];
                        for (int i = end - 1; i >= offset; i--)
                            out[offset + --bufB[in[i] >>> 11 & 31]] = in[i];
                    }
                    offset += size;
                }
            }
            int jump = out.length >>> numCuts, mid = 0, assigned = 0;
            double fr = 270.0 / (jump * 31.0);
            for (int n = (1 << numCuts) - 1; assigned < n; assigned++, mid += jump) {
                double r = 0, g = 0, b = 0;
                for (int i = mid + jump - 1; i >= mid; i--) {
                    color = out[i];
                    r += color >>> 27;
                    g += color >>> 19 & 31;
                    b += color >>> 11 & 31;
                }
                paletteArray[assigned] =
                        Math.min(Math.max((int)((r - 7.0) * fr), 0), 255) << 24 |
                                Math.min(Math.max((int)((g - 7.0) * fr), 0), 255) << 16 |
                                Math.min(Math.max((int)((b - 7.0) * fr), 0), 255) << 8 | 0xFF;
            }
            {
                int j2 = out.length - (mid - jump);
                double r = 0, g = 0, b = 0, fr2 = 270.0 / (j2 * 31.0);
                for (int i = out.length - 1; i >= mid; i--) {
                    color = out[i];
                    r += color >>> 27;
                    g += color >>> 19 & 31;
                    b += color >>> 11 & 31;
                }
                paletteArray[assigned++] =
                        Math.min(Math.max((int)((r - 7.0) * fr2), 0), 255) << 24 |
                                Math.min(Math.max((int)((g - 7.0) * fr2), 0), 255) << 16 |
                                Math.min(Math.max((int)((b - 7.0) * fr2), 0), 255) << 8 | 0xFF;
            }
//            int jump = out.length >>> numCuts, mid = jump >>> 1, assigned = 0;
//            for (int n = 1 << numCuts; assigned < n; assigned++, mid += jump) {
//                paletteArray[assigned] = out[mid];
//            }
            COLORS:
            for (int i = limit; i < assigned; i++) {
                int currentCount = counts.get(paletteArray[i], 0);
                for (int j = 0; j < limit; j++) {
                    if(counts.get(paletteArray[j], 0) < currentCount)
                    {
                        int temp = paletteArray[j];
                        paletteArray[j] = paletteArray[i];
                        paletteArray[i] = temp;
                        continue COLORS;
                    }
                }
            }
            if(hasTransparent == 1) {
                int min = Integer.MAX_VALUE, worst = 0;
                for (int i = 0; i < limit; i++) {
                    int currentCount = counts.get(paletteArray[i], 0);
                    if(currentCount < min){
                        min = currentCount;
                        worst = i;
                    }
                }
                if (worst != 0) {
                    paletteArray[worst] = paletteArray[0];
                }
                paletteArray[0] = 0;
            }
//            COLORS:
//            for (; mid < out.length; mid += jump) {
//                int currentCount = counts.get(out[mid], 0);
//                for (int i = limit - 1; i > hasTransparent; i--) {
//                    if(counts.get(paletteArray[i], 0) < currentCount)
//                    {
//                        paletteArray[i] = out[mid];
//                        continue COLORS;
//                    }
//                }
//            }
            for (int i = hasTransparent; i < limit; i++) {
                color = paletteArray[i];
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
            }
            colorCount = limit;
            populationBias = (float) Math.exp(-1.375/colorCount);
        }
        else
        {
            IntIntMap.Keys it = counts.keys();
            Arrays.fill(paletteArray, 0);
            for (int i = hasTransparent; i < limit && it.hasNext; i++) {
                paletteArray[i] = color = it.next();
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
            }
            colorCount = counts.size + hasTransparent;
            populationBias = (float) Math.exp(-1.375/colorCount);
        }

        int c2;
        int rr, gg, bb;
        double dist;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = Double.POSITIVE_INFINITY;
                        for (int i = 1; i < colorCount; i++) {
                            if (dist > (dist = Math.min(dist, differenceAnalyzing(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
    }

    protected static boolean bigPaletteLoaded = false;
    protected static char[] bigPaletteMapping;

    /**
     * Builds a mapping from RGB555 colors to their closest match in {@code palette} by calculating the closest match
     * for every color. The {@code palette} should have 1024 colors, if possible, but can be smaller.The mapping this
     * makes is only used by the "Reductive" analysis methods, such as {@link #analyzeReductive(Pixmap, double, int)}.
     * Note that while this method is not static, the palette mapping it
     * stores its result in is static, so you should avoid calling this on multiple threads.
     *
     * @param palette a typically-1024-color RGBA8888 palette; may be smaller, but not larger
     */
    public void alterBigPalette(int[] palette) {
        if(bigPaletteMapping == null) bigPaletteMapping = new char[0x8000];
        final int plen = palette.length;
        // Check reference equality to avoid medium-large copy when building from existing BIG_PALETTE
        if(palette != BIG_PALETTE)
            System.arraycopy(palette, 0, BIG_PALETTE, 0, Math.min(plen, BIG_PALETTE.length));
        if(plen < BIG_PALETTE.length)
            Arrays.fill(BIG_PALETTE, plen, 1024, 0);
        int color, c2;
        double dist;
        for (int i = 0; i < plen; i++) {
            color = palette[i];
            if ((color & 0x80) != 0) {
                bigPaletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (char) i;
            }
        }
        int rr, gg, bb;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (bigPaletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = 1E100;
                        for (int i = 1; i < plen; i++) {
                            if (dist > (dist = Math.min(dist, differenceMatch(BIG_PALETTE[i], rr, gg, bb))))
                                bigPaletteMapping[c2] = (char) i;
                        }
                    }
                }
            }
        }
        bigPaletteLoaded = true;
    }

    /**
     * Writes the current {@link #bigPaletteMapping} to the given FileHandle. If {@code filename} is null, this writes
     * to the local FileHandle {@code "BigPaletteMapping.dat"} .
     * The palette file can be used by anim8-gdx itself if
     * its name is {@code "BigPaletteMapping.dat"} and stored in the classpath root, or it can be loaded with
     * {@link #loadBigPalette(FileHandle, int[])}.
     * @param filename may be null to write to {@code "BigPaletteMapping.dat"}, or otherwise a FileHandle to write to
     */
    public void writeBigPalette(FileHandle filename){
        if(Gdx.files != null && bigPaletteMapping != null)
            (filename == null ? Gdx.files.local("BigPaletteMapping.dat") : filename).writeString(new String(bigPaletteMapping), false, "UTF8");

    }

    /**
     * Builds the mapping from RGB555 colors to their closest match in {@code palette} by loading the known mapping
     * from the given file. This changes {@link #BIG_PALETTE} to use {@code palette}; as such, {@code palette} should
     * have at most 1024 colors. The mapping this makes is only used by the "Reductive" analysis methods, such as
     * {@link #analyzeReductive(Pixmap, double, int)}. You typically obtain a palette data file when you call
     * {@link #writeBigPalette(FileHandle)}, and it can be passed here along with the palette array passed to
     * {@link #alterBigPalette(int[])} (which does the setup necessary for writeBigPalette()).
     * Note that while this method is not static, the palette mapping it stores its result in is static, so you should
     * avoid calling this on multiple threads.
     *
     * @param file the FileHandle to load; typically output by {@link #alterBigPalette(int[])} previously
     * @param palette an array of RGBA8888 int colors; should have length 1024 at most
     */
    public void loadBigPalette(FileHandle file, int[] palette) {
        final int plen = palette.length;
        System.arraycopy(palette, 0, BIG_PALETTE, 0, Math.min(plen, BIG_PALETTE.length));
        if(plen < BIG_PALETTE.length)
            Arrays.fill(BIG_PALETTE, plen, 1024, 0);
        if(bigPaletteMapping == null) bigPaletteMapping = new char[0x8000];
        file.readString("UTF8").getChars(0, 0x8000, bigPaletteMapping, 0);
        bigPaletteLoaded = true;
    }

    /**
     * Builds the mapping from RGB555 colors to their closest match in {@link #BIG_PALETTE} by loading the known mapping
     * from an optional file, or assembling a new mapping if that file is not present. The file this needs to run using
     * its "fast path" is {@code BigPaletteMapping.dat}, which must be in the resources root to be loaded successfully
     * (in a libGDX project, this is {@code /assets/}). You can download this file from
     * <a href="https://github.com/tommyettinger/anim8-gdx/blob/master/optional/BigPaletteMapping.dat">the optional folder of the anim8-gdx repo</a>
     * or create it yourself by calling this method at least once to
     * assemble a new mapping, then calling {@link #writeBigPalette(FileHandle)} to create a file that you can then put
     * in your resources root with the filename as stated before.
     * <br>
     * This will not work as intended if {@link #BIG_PALETTE} has been altered, such as by using
     * {@link #alterBigPalette(int[])}. The mapping this makes is only used by the "Reductive" analysis methods, such as
     * {@link #analyzeReductive(Pixmap, double, int)}. If the big palette has already been loaded successfully, this
     * does nothing and returns immediately (it checks the static field {@link #bigPaletteLoaded}).
     * Note that while this method is not static, the palette mapping it stores its result in is static, so you should
     * avoid calling methods that modify {@link #bigPaletteMapping} on other threads (such as
     * {@link #alterBigPalette(int[])} and {@link #loadBigPalette(FileHandle, int[])}).
     */
    public void buildBigPalette() {
        if(bigPaletteLoaded) return;
        if(bigPaletteMapping == null) bigPaletteMapping = new char[0x8000];
        FileHandle dat = Gdx.files.classpath("BigPaletteMapping.dat");
        if(!dat.exists()) {
            alterBigPalette(BIG_PALETTE);
            return;
        }
        dat.readString("UTF8").getChars(0, 0x8000, bigPaletteMapping, 0);
        bigPaletteLoaded = true;
    }

    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most 256 colors if there are
     * too many colors to store in a PNG-8 or GIF palette.
     * This always uses colors from the 1024-color {@link #BIG_PALETTE} palette (with at most one transparent
     * color, and no alpha for other colors); this will always reserve a palette entry for transparent (even if the
     * image has no transparency) because it uses palette index 0 in its analysis step. Because calling
     * {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that aren't exact, and dithering
     * works better when the palette can choose colors that are sufficiently different, this takes a threshold value to
     * determine whether it should permit a less-common color into the palette, and if the second color is different
     * enough (as measured by {@link #differenceAnalyzing(int, int)} ) by a value of at least 400, it is allowed in
     * the palette, otherwise it is kept out for being too similar to existing colors. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to {@link #reduce(Pixmap)} a
     * Pixmap.
     * <br>
     * This has a small delay when first called, because it needs to build a mapping for the large {@link #BIG_PALETTE}
     * palette. This only needs to be done once per program (the result is saved). You can also (in your project) copy
     * a precalculated mapping into your resources root, as described in {@link #buildBigPalette()}.
     *
     * @param pixmap a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     */
    public void analyzeReductive(Pixmap pixmap) {
        analyzeReductive(pixmap, 100);
    }

    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most 256 colors if there are
     * too many colors to store in a PNG-8 or GIF palette.
     * This always uses colors from the 1024-color {@link #BIG_PALETTE} palette (with at most one transparent
     * color, and no alpha for other colors); this will always reserve a palette entry for transparent (even if the
     * image has no transparency) because it uses palette index 0 in its analysis step. Because calling
     * {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that aren't exact, and dithering
     * works better when the palette can choose colors that are sufficiently different, this takes a threshold value to
     * determine whether it should permit a less-common color into the palette, and if the second color is different
     * enough (as measured by {@link #differenceAnalyzing(int, int)} ) by a value of at least {@code threshold}, it is allowed in
     * the palette, otherwise it is kept out for being too similar to existing colors. The threshold is usually between
     * 50 and 200, and 100 is a good default. If the threshold is too high, then some colors that would be useful to
     * smooth out subtle color changes won't get considered, and colors may change more abruptly. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to {@link #reduce(Pixmap)} a
     * Pixmap.
     * <br>
     * This has a small delay when first called, because it needs to build a mapping for the large {@link #BIG_PALETTE}
     * palette. This only needs to be done once per program (the result is saved). You can also (in your project) copy
     * a precalculated mapping into your resources root, as described in {@link #buildBigPalette()}.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)} ; usually between 50 and 200, 100 is a good default
     */
    public void analyzeReductive(Pixmap pixmap, double threshold) {
        analyzeReductive(pixmap, threshold, 256);
    }

    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most {@code limit} colors.
     * This always uses colors from the 1024-color {@link #BIG_PALETTE} palette (with at most one transparent
     * color, and no alpha for other colors); this will always reserve a palette entry for transparent (even if the
     * image has no transparency) because it uses palette index 0 in its analysis step. Because calling
     * {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that aren't exact, and dithering
     * works better when the palette can choose colors that are sufficiently different, this takes a threshold value to
     * determine whether it should permit a less-common color into the palette, and if the second color is different
     * enough (as measured by {@link #differenceAnalyzing(int, int)} ) by a value of at least {@code threshold}, it is allowed in
     * the palette, otherwise it is kept out for being too similar to existing colors. The threshold is usually between
     * 50 and 200, and 100 is a good default. If the threshold is too high, then some colors that would be useful to
     * smooth out subtle color changes won't get considered, and colors may change more abruptly. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to {@link #reduce(Pixmap)} a
     * Pixmap.
     * <br>
     * This has a small delay when first called, because it needs to build a mapping for the large {@link #BIG_PALETTE}
     * palette. This only needs to be done once per program (the result is saved). You can also (in your project) copy
     * a precalculated mapping into your resources root, as described in {@link #buildBigPalette()}.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 200, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyzeReductive(Pixmap pixmap, double threshold, int limit) {
        buildBigPalette();
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 2), 256);
        threshold /= Math.min(0.3, Math.pow(limit + 16, 1.45) * 0.00013333);
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntIntMap counts = new IntIntMap(limit);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                color = pixmap.getPixel(x, y) & 0xF8F8F880;
                if ((color & 0x80) != 0) {
                    color = BIG_PALETTE[bigPaletteMapping[shrink(color)]];
                    counts.getAndIncrement(color, 0, 1);
                }
            }
        }
        int cs = counts.size;
        Array<IntIntMap.Entry> es = new Array<>(cs);
        for(IntIntMap.Entry e : counts)
        {
            IntIntMap.Entry e2 = new IntIntMap.Entry();
            e2.key = e.key;
            e2.value = e.value;
            es.add(e2);
        }
        es.sort(entryComparator);
        if (cs < limit) {
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            while (i < limit && c < cs) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    if (differenceAnalyzing(color, paletteArray[j]) < threshold)
                        continue PER_BEST;
                }
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        }

        int c2;
        int rr, gg, bb;
        double dist;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = Double.MAX_VALUE;
                        for (int i = 1; i < colorCount; i++) {
                            if (dist > (dist = Math.min(dist, differenceAnalyzing(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
    }


    public int blend(int rgba1, int rgba2, float preference) {
        int a1 = rgba1 & 255, a2 = rgba2 & 255;
        if((a1 & 0x80) == 0) return rgba2;
        else if((a2 & 0x80) == 0) return rgba1;
        rgba1 = shrink(rgba1);
        rgba2 = shrink(rgba2);
        float L = OKLAB[0][rgba1] + (OKLAB[0][rgba2] - OKLAB[0][rgba1]) * preference;
        float A = OKLAB[1][rgba1] + (OKLAB[1][rgba2] - OKLAB[1][rgba1]) * preference;
        float B = OKLAB[2][rgba1] + (OKLAB[2][rgba2] - OKLAB[2][rgba1]) * preference;
        return oklabToRGB(L, A, B, (a1 + (a2 - a1) * preference) * (1f/255f));
    }

    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most 256 colors. If there are 256 or fewer colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * 256 colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by
     * {@link #differenceAnalyzing(int, int, int, int)}) by a
     * value of at least 300, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. This doesn't return a value but instead stores the palette info in this object; a PaletteReducer can be
     * assigned to the {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     */
    public void analyze(Array<Pixmap> pixmaps){
        analyze(pixmaps.toArray(Pixmap.class), pixmaps.size, 100, 256);
    }

    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most 256 colors. If there are 256 or fewer colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * 256 colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #differenceAnalyzing(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 50 and 200, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 200, 100 is a good default
     */
    public void analyze(Array<Pixmap> pixmaps, double threshold){
        analyze(pixmaps.toArray(Pixmap.class), pixmaps.size, threshold, 256);
    }

    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. If there are {@code limit} or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * {@code limit} colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #differenceAnalyzing(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 50 and 200, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 200, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyze(Array<Pixmap> pixmaps, double threshold, int limit){
        analyze(pixmaps.toArray(Pixmap.class), pixmaps.size, threshold, limit);
    }
    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. If there are {@code limit} or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * {@code limit} colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #differenceAnalyzing(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 50 and 200, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param pixmapCount the maximum number of Pixmap entries in pixmaps to use
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 200, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyze(Pixmap[] pixmaps, int pixmapCount, double threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 2), 256);
        threshold /= Math.min(0.45, Math.pow(limit + 16, 1.45) * 0.0002);
        IntIntMap counts = new IntIntMap(limit);
        int[] reds = new int[limit], greens = new int[limit], blues = new int[limit];
        for (int i = 0; i < pixmapCount && i < pixmaps.length; i++) {
            Pixmap pixmap = pixmaps[i];
            final int width = pixmap.getWidth(), height = pixmap.getHeight();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    color = pixmap.getPixel(x, y) & 0xF8F8F880;
                    if ((color & 0x80) != 0) {
                        color |= (color >>> 5 & 0x07070700) | 0xFF;
                        counts.getAndIncrement(color, 0, 1);
                    }
                }
            }
        }
        final int cs = counts.size;
        Array<IntIntMap.Entry> es = new Array<>(cs);
        for(IntIntMap.Entry e : counts)
        {
            IntIntMap.Entry e2 = new IntIntMap.Entry();
            e2.key = e.key;
            e2.value = e.value;
            es.add(e2);
        }
        es.sort(entryComparator);
        if (cs < limit) {
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                reds[i] = color >>> 24;
                greens[i] = color >>> 16 & 255;
                blues[i] = color >>> 8 & 255;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            for (; i < limit && c < cs;) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    double diff = differenceAnalyzing(color, paletteArray[j]);
                    if (diff < threshold)
                        continue PER_BEST;
                }
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                reds[i] = color >>> 24;
                greens[i] = color >>> 16 & 255;
                blues[i] = color >>> 8 & 255;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        }

        int c2;
        int rr, gg, bb;
        double dist;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = Double.MAX_VALUE;
                        for (int i = 1; i < colorCount; i++) {
                            if (dist > (dist = Math.min(dist, differenceAnalyzing(reds[i], greens[i], blues[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
    }

    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most 256 colors. If there are 256 or fewer colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * 256 colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by
     * {@link #differenceHW(int, int, int, int)}) by a
     * value of at least 500, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. This doesn't return a value but instead stores the palette info in this object; a PaletteReducer can be
     * assigned to the {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     */
    public void analyzeHueWise(Array<Pixmap> pixmaps){
        analyzeHueWise(pixmaps.toArray(Pixmap.class), pixmaps.size, 100, 256);
    }

    /**
     * Analyzes all of the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most 256 colors. If there are 256 or fewer colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * 256 colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #differenceHW(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 50 and 200, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceHW(int, int)}; usually between 50 and 200, 100 is a good default
     */
    public void analyzeHueWise(Array<Pixmap> pixmaps, double threshold){
        analyzeHueWise(pixmaps.toArray(Pixmap.class), pixmaps.size, threshold, 256);
    }
    /**
     * Analyzes all of the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. If there are {@code limit} or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * {@code limit} colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #differenceHW(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 50 and 200, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceHW(int, int)}; usually between 50 and 200, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyzeHueWise(Array<Pixmap> pixmaps, double threshold, int limit){
        analyzeHueWise(pixmaps.toArray(Pixmap.class), pixmaps.size, threshold, limit);
    }
    /**
     * Analyzes all of the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. If there are {@code limit} or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * {@code limit} colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #differenceHW(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 50 and 200, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param pixmapCount the maximum number of Pixmap entries in pixmaps to use
     * @param threshold a minimum color difference as produced by {@link #differenceHW(int, int)}; usually between 50 and 200, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyzeHueWise(Pixmap[] pixmaps, int pixmapCount, double threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 3), 256);
        threshold /= Math.pow(limit, 1.35) * 0.000043;
        final int w0 = pixmaps[0].getWidth(), h0 = pixmaps[0].getHeight();
        IntIntMap counts = new IntIntMap(limit);
        IntArray enc = new IntArray(w0 * h0 * pixmapCount / 10);
        int[] reds = new int[limit], greens = new int[limit], blues = new int[limit];
        for (int i = 0; i < pixmapCount && i < pixmaps.length; i++) {
            Pixmap pixmap = pixmaps[i];
            final int width = pixmap.getWidth(), height = pixmap.getHeight();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    color = pixmap.getPixel(x, y) & 0xF8F8F880;
                    if ((color & 0x80) != 0) {
                        color |= (color >>> 5 & 0x07070700) | 0xFF;
                        counts.getAndIncrement(color, 0, 1);
                        if(((x & y) * 5 - i & 31) < 3)
                            enc.add(shrink(color));
                    }
                }
            }
        }
        final int cs = counts.size;
        if (cs < limit) {
            Array<IntIntMap.Entry> es = new Array<>(cs);
            for(IntIntMap.Entry e : counts)
            {
                IntIntMap.Entry e2 = new IntIntMap.Entry();
                e2.key = e.key;
                e2.value = e.value;
                es.add(e2);
            }
            es.sort(entryComparator);
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                reds[i] = color >>> 24;
                greens[i] = color >>> 16 & 255;
                blues[i] = color >>> 8 & 255;
                i++;
            }
            colorCount = i;
        }
        else // generate colors
        {
            final int[] ei = enc.items;
            sort(ei, 0, enc.size, hueComparator);
            paletteArray[1] = -1; // white
            reds[1] = 255;
            greens[1] = 255;
            blues[1] = 255;
            paletteArray[2] = 255; // black
            reds[2] = 0;
            greens[2] = 0;
            blues[2] = 0;
            int i = 3, encs = enc.size, segments = Math.min(encs, limit - 3) + 1 >> 1, e = 0;
            double lightPieces = Math.ceil(Math.log(limit));
            PER_BEST:
            for (int s = 0; i < limit; s++) {
                if(e > (e %= encs)){
                    segments++;
                    lightPieces++;
                    threshold *= 0.9;
                }
                s %= segments;
                int segStart = e, segEnd = Math.min(segStart + (int)Math.ceil(encs / (double)segments), encs), segLen = segEnd - segStart;
                sort(ei, segStart, segLen, lightnessComparator);
                for (int li = 0; li < lightPieces && li < segLen && i < limit; li++) {
                    int start = e, end = Math.min(encs, start + (int)Math.ceil(segLen / lightPieces)), len = end - start;

                    float totalL = 0.0f, totalA = 0.0f, totalB = 0.0f;
                    for (; e < end; e++) {
                        int index = ei[e];
                        totalL += OKLAB[0][index];
                        totalA += OKLAB[1][index];
                        totalB += OKLAB[2][index];
                    }
                    totalA /= len;
                    totalB /= len;
                    color = oklabToRGB(
                            OtherMath.barronSpline(totalL / len, 3f, 0.5f),
                            totalA,//(OtherMath.cbrt(totalA) + 31f * totalA) * 0x1p-5f,
                            totalB,//(OtherMath.cbrt(totalB) + 31f * totalB) * 0x1p-5f,
                            1f);
//                    (OtherMath.barronSpline(totalA / (len<<1)+0.5f, 2f, 0.5f)-0.5f)*2f,
//                    (OtherMath.barronSpline(totalB / (len<<1)+0.5f, 2f, 0.5f)-0.5f)*2f,

                    for (int j = 3; j < i; j++) {
                        if (differenceHW(color, paletteArray[j]) < threshold)
                            continue PER_BEST;
                    }
                    paletteArray[i] = color;
                    paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                    reds[i] = color >>> 24;
                    greens[i] = color >>> 16 & 255;
                    blues[i] = color >>> 8 & 255;
                    i++;
                }
            }
            colorCount = i;
        }
        populationBias = (float) Math.exp(-1.375/colorCount);

        int c2;
        int rr, gg, bb;
        double dist;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = Double.MAX_VALUE;
                        for (int i = 1; i < colorCount; i++) {
                            if (dist > (dist = Math.min(dist, differenceHW(reds[i], greens[i], blues[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
    }

    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most 256 colors. This always uses colors from the 1024-color
     * {@link #BIG_PALETTE} palette (with at most one transparent color, and no alpha for other colors); this will always
     * reserve a palette entry for transparent (even if the image has no transparency) because it uses palette index 0
     * in its analysis step. Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors
     * that aren't exact, and dithering works better when the palette can choose colors that are sufficiently different,
     * this takes a threshold value to determine whether it should permit a less-common color into the palette, and if
     * the second color is different enough (as measured by {@link #differenceAnalyzing(int, int)}) by a value of at
     * least 400, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     * <br>
     * This has a small delay when first called, because it needs to build a mapping for the large {@link #BIG_PALETTE}
     * palette. This only needs to be done once per program (the result is saved). Future versions of this library may
     * use a precalculated mapping.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     */
    public void analyzeReductive(Array<Pixmap> pixmaps){
        analyzeReductive(pixmaps.toArray(Pixmap.class), pixmaps.size, 100, 256);
    }

    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most 256 colors. This always uses colors from the 1024-color
     * {@link #BIG_PALETTE} palette (with at most one transparent color, and no alpha for other colors); this will always
     * reserve a palette entry for transparent (even if the image has no transparency) because it uses palette index 0
     * in its analysis step. Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors
     * that aren't exact, and dithering works better when the palette can choose colors that are sufficiently different,
     * this takes a threshold value to determine whether it should permit a less-common color into the palette, and if
     * the second color is different enough (as measured by {@link #differenceAnalyzing(int, int)}) by a value of at
     * least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. The threshold is usually between 50 and 200, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     * <br>
     * This has a small delay when first called, because it needs to build a mapping for the large {@link #BIG_PALETTE}
     * palette. This only needs to be done once per program (the result is saved). Future versions of this library may
     * use a precalculated mapping.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 200, 100 is a good default
     */
    public void analyzeReductive(Array<Pixmap> pixmaps, double threshold){
        analyzeReductive(pixmaps.toArray(Pixmap.class), pixmaps.size, threshold, 256);
    }

    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. This always uses colors from the 1024-color
     * {@link #BIG_PALETTE} palette (with at most one transparent color, and no alpha for other colors); this will always
     * reserve a palette entry for transparent (even if the image has no transparency) because it uses palette index 0
     * in its analysis step. Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors
     * that aren't exact, and dithering works better when the palette can choose colors that are sufficiently different,
     * this takes a threshold value to determine whether it should permit a less-common color into the palette, and if
     * the second color is different enough (as measured by {@link #differenceAnalyzing(int, int)}) by a value of at
     * least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. The threshold is usually between 50 and 200, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     * <br>
     * This has a small delay when first called, because it needs to build a mapping for the large {@link #BIG_PALETTE}
     * palette. This only needs to be done once per program (the result is saved). Future versions of this library may
     * use a precalculated mapping.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 200, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyzeReductive(Array<Pixmap> pixmaps, double threshold, int limit){
        analyzeReductive(pixmaps.toArray(Pixmap.class), pixmaps.size, threshold, limit);
    }
    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. This always uses colors from the 1024-color
     * {@link #BIG_PALETTE} palette (with at most one transparent color, and no alpha for other colors); this will always
     * reserve a palette entry for transparent (even if the image has no transparency) because it uses palette index 0
     * in its analysis step. Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors
     * that aren't exact, and dithering works better when the palette can choose colors that are sufficiently different,
     * this takes a threshold value to determine whether it should permit a less-common color into the palette, and if
     * the second color is different enough (as measured by {@link #differenceAnalyzing(int, int)}) by a value of at
     * least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. The threshold is usually between 50 and 200, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     * <br>
     * This has a small delay when first called, because it needs to build a mapping for the large {@link #BIG_PALETTE}
     * palette. This only needs to be done once per program (the result is saved). Future versions of this library may
     * use a precalculated mapping.
     *
     * @param pixmaps   a Pixmap array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param pixmapCount the maximum number of Pixmap entries in pixmaps to use
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 200, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyzeReductive(Pixmap[] pixmaps, int pixmapCount, double threshold, int limit) {
        buildBigPalette();
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 2), 256);
        threshold /= Math.min(0.3, Math.pow(limit + 16, 1.45) * 0.00013333);
        IntIntMap counts = new IntIntMap(limit);
        int[] reds = new int[limit], greens = new int[limit], blues = new int[limit];
        for (int i = 0; i < pixmapCount && i < pixmaps.length; i++) {
            Pixmap pixmap = pixmaps[i];
            final int width = pixmap.getWidth(), height = pixmap.getHeight();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    color = pixmap.getPixel(x, y) & 0xF8F8F880;
                    if ((color & 0x80) != 0) {
                        color = BIG_PALETTE[bigPaletteMapping[shrink(color)]];
                        counts.getAndIncrement(color, 0, 1);
                    }
                }
            }
        }
        final int cs = counts.size;
        Array<IntIntMap.Entry> es = new Array<>(cs);
        for(IntIntMap.Entry e : counts)
        {
            IntIntMap.Entry e2 = new IntIntMap.Entry();
            e2.key = e.key;
            e2.value = e.value;
            es.add(e2);
        }
        es.sort(entryComparator);
        if (cs < limit) {
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                reds[i] = color >>> 24;
                greens[i] = color >>> 16 & 255;
                blues[i] = color >>> 8 & 255;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            for (; i < limit && c < cs;) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    double diff = differenceAnalyzing(color, paletteArray[j]);
                    if (diff < threshold)
                        continue PER_BEST;
                }
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                reds[i] = color >>> 24;
                greens[i] = color >>> 16 & 255;
                blues[i] = color >>> 8 & 255;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        }

        int c2;
        int rr, gg, bb;
        double dist;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = Double.MAX_VALUE;
                        for (int i = 1; i < colorCount; i++) {
                            if (dist > (dist = Math.min(dist, differenceAnalyzing(reds[i], greens[i], blues[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
    }

    /**
     * Gets the "strength" of the dither effect applied during {@link #reduce(Pixmap)} calls. The default is 1f,
     * and while both values higher than 1f and lower than 1f are valid, they should not be negative.
     * If ditherStrength is too high, all sorts of artifacts will appear; if it is too low, the effect of the dither to
     * smooth out changes in color will be very hard to notice.
     * @return the current dither strength; typically near 1.0f and always non-negative
     */
    public float getDitherStrength() {
        return ditherStrength;
    }

    /**
     * Changes the "strength" of the dither effect applied during {@link #reduce(Pixmap)} calls. The default is 1f,
     * and while both values higher than 1f and lower than 1f are valid, they should not be negative. If you want dither
     * to be eliminated, don't set dither strength to 0; use {@link #reduceSolid(Pixmap)} instead of reduce().
     * If ditherStrength is too high, all sorts of artifacts will appear; if it is too low, the effect of the dither to
     * smooth out changes in color will be very hard to notice.
     * @param ditherStrength dither strength as a non-negative float that should be close to 1f
     */
    public void setDitherStrength(float ditherStrength) {
        this.ditherStrength = Math.max(0f, ditherStrength);
    }

    public float getPopulationBias() {
        return populationBias;
    }

    /**
     * Sets the population bias; rarely needed externally.
     * Typically, the population bias is between 0.5 and 1, closer to 1 with larger palette sizes, and closer to 0.5
     * with smaller palettes.
     * <br>
     * Within anim8-gdx, this is generally calculated with {@code (float)Math.exp(-1.375 / colorCount)}, where
     * {@link #colorCount} is already known and between 2 and 256, inclusive.
     *
     * @param populationBias a population bias value, which is almost always between 0.5 and 1.0
     */
    public void setPopulationBias(float populationBias) {
        this.populationBias = populationBias;
    }

    /**
     * Modifies the given Pixmap so that it only uses colors present in this PaletteReducer, dithering when it can by
     * using Overboard dithering (this merely delegates to {@link #reduceOverboard(Pixmap)}).
     * If you want to reduce the colors in a Pixmap based on what it currently contains, call
     * {@link #analyze(Pixmap)} with {@code pixmap} as its argument, then call this method with the same
     * Pixmap. You may instead want to use a known palette instead of one computed from a Pixmap;
     * {@link #exact(int[])} is the tool for that job.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduce (Pixmap pixmap) {
        return reduceOverboard(pixmap);
    }

    /**
     * Uses the given {@link Dithered.DitherAlgorithm} to decide how to dither {@code pixmap}.
     * @param pixmap a pixmap that will be modified in-place
     * @param ditherAlgorithm a dithering algorithm enum value; if not recognized, defaults to {@link Dithered.DitherAlgorithm#OVERBOARD}
     * @return {@code pixmap} after modifications
     */
    public Pixmap reduce(Pixmap pixmap, Dithered.DitherAlgorithm ditherAlgorithm){
        if(pixmap == null) return null;
        if(ditherAlgorithm == null) return reduceOverboard(pixmap);
        switch (ditherAlgorithm) {
            case NONE:
                return reduceSolid(pixmap);
            case GRADIENT_NOISE:
                return reduceJimenez(pixmap);
            case PATTERN:
                return reduceKnoll(pixmap);
            case CHAOTIC_NOISE:
                return reduceChaoticNoise(pixmap);
            case DIFFUSION:
                return reduceFloydSteinberg(pixmap);
            case BLUE_NOISE:
                return reduceBlueNoise(pixmap); 
            case SCATTER:
                return reduceScatter(pixmap);
            case ROBERTS:
                return reduceRoberts(pixmap);
            case WOVEN:
                return reduceWoven(pixmap);
            case DODGY:
                return reduceDodgy(pixmap);
            case LOAF:
                return reduceLoaf(pixmap);
            case NEUE:
                return reduceNeue(pixmap);
            case BURKES:
                return reduceBurkes(pixmap);
            case WREN:
                return reduceWren(pixmap);
            case OCEANIC:
                return reduceOceanic(pixmap);
            case SEASIDE:
                return reduceSeaside(pixmap);
            case GOURD:
                return reduceGourd(pixmap);
            case OVERBOARD:
            default:
                return reduceOverboard(pixmap);
        }
    }

    /**
     * Modifies the given Pixmap so it only uses colors present in this PaletteReducer, without dithering. This produces
     * blocky solid sections of color in most images where the palette isn't exact, instead of checkerboard-like
     * dithering patterns. If you want to reduce the colors in a Pixmap based on what it currently contains, call
     * {@link #analyze(Pixmap)} with {@code pixmap} as its argument, then call this method with the same
     * Pixmap. You may instead want to use a known palette instead of one computed from a Pixmap;
     * {@link #exact(int[])} is the tool for that job.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduceSolid (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color;
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    int rr = ((color >>> 24)       );
                    int gg = ((color >>> 16) & 0xFF);
                    int bb = ((color >>> 8)  & 0xFF);
                    pixmap.drawPixel(px, y, paletteArray[
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))] & 0xFF]);
                }
            }

        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Modifies the given Pixmap so that it only uses colors present in this PaletteReducer, dithering when it can with
     * Sierra Lite dithering instead of the Floyd-Steinberg dithering that {@link #reduce(Pixmap)} uses.
     * If you want to reduce the colors in a Pixmap based on what it currently contains, call
     * {@link #analyze(Pixmap)} with {@code pixmap} as its argument, then call this method with the same
     * Pixmap. You may instead want to use a known palette instead of one computed from a Pixmap;
     * {@link #exact(int[])} is the tool for that job.
     * <p>
     * This method is similar to Floyd-Steinberg, since both are error-diffusion dithers. Sometimes Sierra Lite can
     * avoid unpleasant artifacts in Floyd-Steinberg, so it's better in the worst-case, but it isn't usually as good in
     * its best-case.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduceSierraLite (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        float ditherStrength = this.ditherStrength * 20, halfDitherStrength = ditherStrength * 0.5f;
        for (int y = 0; y < h; y++) {
            int ny = y + 1;

            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, lineLen);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, lineLen);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);

            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    er = curErrorRed[px];
                    eg = curErrorGreen[px];
                    eb = curErrorBlue[px];
                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                    used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (0x2.4p-8f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x2.4p-8f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x2.4p-8f * ((color>>>8&255)- (used>>>8&255)) );
                    rdiff *= 1.25f / (0.25f + Math.abs(rdiff));
                    gdiff *= 1.25f / (0.25f + Math.abs(gdiff));
                    bdiff *= 1.25f / (0.25f + Math.abs(bdiff));



                    if(px < lineLen - 1)
                    {
                        curErrorRed[px+1]   += rdiff * ditherStrength;
                        curErrorGreen[px+1] += gdiff * ditherStrength;
                        curErrorBlue[px+1]  += bdiff * ditherStrength;
                    }
                    if(ny < h)
                    {
                        if(px > 0)
                        {
                            nextErrorRed[px-1]   += rdiff * halfDitherStrength;
                            nextErrorGreen[px-1] += gdiff * halfDitherStrength;
                            nextErrorBlue[px-1]  += bdiff * halfDitherStrength;
                        }
                        nextErrorRed[px]   += rdiff * halfDitherStrength;
                        nextErrorGreen[px] += gdiff * halfDitherStrength;
                        nextErrorBlue[px]  += bdiff * halfDitherStrength;
                    }
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Modifies the given Pixmap so that it only uses colors present in this PaletteReducer, dithering when it can with
     * the commonly-used Floyd-Steinberg dithering. If you want to reduce the colors in a Pixmap based on what it
     * currently contains, call {@link #analyze(Pixmap)} with {@code pixmap} as its argument, then call this method with
     * the same Pixmap. You may instead want to use a known palette instead of one computed from a Pixmap;
     * {@link #exact(int[])} is the tool for that job.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduceFloydSteinberg (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float w1 = ditherStrength * 32 / populationBias, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;
        for (int y = 0; y < h; y++) {
            int ny = y + 1;

            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, lineLen);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, lineLen);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);

            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + curErrorRed[px]  , 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + curErrorGreen[px], 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + curErrorBlue[px] , 0), 1023)] & 255;
//                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + curErrorRed[px]   + 0.5f), 0), 0xFF);
//                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + curErrorGreen[px] + 0.5f), 0), 0xFF);
//                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + curErrorBlue[px]  + 0.5f), 0), 0xFF);
                    used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (0x1p-8f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x1p-8f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x1p-8f * ((color>>>8&255)- (used>>>8&255)) );
                    // this alternate code used a sigmoid function to smoothly limit error.
//                    rdiff = (0x1.8p-8f * ((color>>>24)-    (used>>>24))    );
//                    gdiff = (0x1.8p-8f * ((color>>>16&255)-(used>>>16&255)));
//                    bdiff = (0x1.8p-8f * ((color>>>8&255)- (used>>>8&255)) );
//                    rdiff *= 1.25f / (0.25f + Math.abs(rdiff));
//                    gdiff *= 1.25f / (0.25f + Math.abs(gdiff));
//                    bdiff *= 1.25f / (0.25f + Math.abs(bdiff));
                    if(px < lineLen - 1)
                    {
                        curErrorRed[px+1]   += rdiff * w7;
                        curErrorGreen[px+1] += gdiff * w7;
                        curErrorBlue[px+1]  += bdiff * w7;
                    }
                    if(ny < h)
                    {
                        if(px > 0)
                        {
                            nextErrorRed[px-1]   += rdiff * w3;
                            nextErrorGreen[px-1] += gdiff * w3;
                            nextErrorBlue[px-1]  += bdiff * w3;
                        }
                        if(px < lineLen - 1)
                        {
                            nextErrorRed[px+1]   += rdiff * w1;
                            nextErrorGreen[px+1] += gdiff * w1;
                            nextErrorBlue[px+1]  += bdiff * w1;
                        }
                        nextErrorRed[px]   += rdiff * w5;
                        nextErrorGreen[px] += gdiff * w5;
                        nextErrorBlue[px]  += bdiff * w5;
                    }
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * It's interleaved gradient noise, by Jorge Jimenez! It's very fast! It's an ordered dither!
     * It's pretty good with gradients, though it may introduce artifacts. It has noticeable diagonal
     * lines in some places, but these tend to have mixed directions that obscure larger patterns.
     * This is very similar to {@link #reduceRoberts(Pixmap)}, but has different artifacts, and this
     * dither tends to be stronger by default. This uses a simpler version of IGN by Job van der Zwan
     * <a href="https://observablehq.com/d/92bc9c793858b2d7">as seen here</a>. It offsets each IGN
     * value by a different amount per-channel, which allows each channel to produce some colors they
     * normally wouldn't be able to with one IGN amount per pixel.
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceJimenez(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color;
//        final float strength = 50f * ditherStrength * (float) Math.pow(populationBias, -2f);
//        final float strength = Math.min(0.63f * ditherStrength / (populationBias * populationBias), 1f);
//        final float strength = Math.min(ditherStrength * populationBias, 1f);
        final float strength = Math.min(ditherStrength * (2f - (populationBias * populationBias * populationBias * populationBias - 0.1598797460796939f) * ((2f * 0.875f) / 0.8188650241570136f)), 1f);
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    // The original IGN for shaders:
                    // fract(fract(v_texCoords.xy * vec2(6.711056, 0.583715)) * 52.9829189)

                    // The original IGN, scaled by about 100x each way for pixels in an image:
//                    float adj = (px * 0.06711056f + y * 0.00583715f);
//                    adj -= (int) adj;
//                    adj *= 52.9829189f;
//                    adj -= (int) adj;
//                    adj -= 0.5f;
//                    adj *- strength;
//                    adj += 0.5f; // for rounding

                    // this is the 8-bit approximation to IGN:
                    // https://observablehq.com/d/92bc9c793858b2d7
//                    float adj = ((142 * px + 79 * y & 255) - 127.5f) * strength;


//                    int xy = 142 * px + 79 * y & 255;

//                    int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + Math.min(Math.max(OtherMath.probitF((xy ^ 0x96) * (1f / 255f)) * strength, -100f), 100f))] & 255;
//                    int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + Math.min(Math.max(OtherMath.probitF((xy ^ 0xA3) * (1f / 255f)) * strength, -100f), 100f))] & 255;
//                    int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + Math.min(Math.max(OtherMath.probitF((xy ^ 0xC9) * (1f / 255f)) * strength, -100f), 100f))] & 255;

//                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + ((xy ^ 0x96) - 127.5f) * strength), 0), 255);
//                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + ((xy ^ 0xA3) - 127.5f) * strength), 0), 255);
//                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + ((xy ^ 0xC9) - 127.5f) * strength), 0), 255);
//
                    int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + ((142 * px + 79 * (y - 0x96) & 255) - 127.5f) * strength)] & 255;
                    int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + ((142 * px + 79 * (y - 0xA3) & 255) - 127.5f) * strength)] & 255;
                    int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + ((142 * px + 79 * (y - 0xC9) & 255) - 127.5f) * strength)] & 255;

                    pixmap.drawPixel(px, y, paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    public Pixmap reduceIgneous(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        float w1 = (6f * ditherStrength * populationBias * populationBias), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = 60f * ditherStrength / (populationBias * populationBias),
                adj;

        for (int y = 0; y < h; y++) {
            int ny = y + 1;

            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, lineLen);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, lineLen);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);

            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    adj = (px * 0.06711056f + y * 0.00583715f);
                    adj -= (int) adj;
                    adj *= 52.9829189f;
                    adj -= (int) adj;
                    adj -= 0.5f;
                    adj *= strength;

                    er = adj + (curErrorRed[px]);
                    eg = adj + (curErrorGreen[px]);
                    eb = adj + (curErrorBlue[px]);

                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (0x3p-10f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x3p-10f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x3p-10f * ((color>>>8&255)- (used>>>8&255)) );

                    if(px < lineLen - 1)
                    {
                        curErrorRed[px+1]   += rdiff * w7;
                        curErrorGreen[px+1] += gdiff * w7;
                        curErrorBlue[px+1]  += bdiff * w7;
                    }
                    if(ny < h)
                    {
                        if(px > 0)
                        {
                            nextErrorRed[px-1]   += rdiff * w3;
                            nextErrorGreen[px-1] += gdiff * w3;
                            nextErrorBlue[px-1]  += bdiff * w3;
                        }
                        if(px < lineLen - 1)
                        {
                            nextErrorRed[px+1]   += rdiff * w1;
                            nextErrorGreen[px+1] += gdiff * w1;
                            nextErrorBlue[px+1]  += bdiff * w1;
                        }
                        nextErrorRed[px]   += rdiff * w5;
                        nextErrorGreen[px] += gdiff * w5;
                        nextErrorBlue[px]  += bdiff * w5;
                    }
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * An ordered dither that uses a sub-random sequence by Martin Roberts to disperse lightness adjustments across the
     * image. This is very similar to {@link #reduceJimenez(Pixmap)}, but is milder by default, and has subtly different
     * artifacts. This should look excellent for animations, especially with small palettes, but the lightness
     * adjustments may be noticeable even in very large palettes.
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceRoberts (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color;
//        float str = (32f * ditherStrength / (populationBias * populationBias));
//        float str = (float) (64 * ditherStrength / Math.log(colorCount * 0.3 + 1.5));
//        float str = 32 * ditherStrength / (populationBias * populationBias * populationBias * populationBias);
        final float str = Math.min(Math.max(48 * ditherStrength / (populationBias * populationBias * populationBias * populationBias), -127), 127);
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    // used in 0.3.10
//                    // Gets R2-based noise and puts it in the -0.75 to 0.75 range
//                    float adj = (px * 0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL >>> 41) * 0x1.8p-23f - 0.75f;
//                    adj = adj * str + 0.5f;
//                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + adj), 0), 255);
//                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + adj), 0), 255);
//                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + adj), 0), 255);

                    // other options
//                    // sign-preserving square root, emphasizes extremes
////                    adj = Math.copySign((float) Math.sqrt(Math.abs(adj)), adj);
//                    // sign-preserving square, emphasizes low-magnitude values
////                    adj *= Math.abs(adj);
                    // Used in 0.3.13, has a heavy color bias
//                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + ((((px-1) * 0xC13FA9A902A6328FL + (y+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-22f - 0x1.4p0f) * str + 0.5f), 0), 255);
//                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + ((((px+3) * 0xC13FA9A902A6328FL + (y-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-22f - 0x1.4p0f) * str + 0.5f), 0), 255);
//                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + ((((px-4) * 0xC13FA9A902A6328FL + (y+2) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-22f - 0x1.4p0f) * str + 0.5f), 0), 255);

//                    int rr = ((color >>> 24)       );
//                    int gg = ((color >>> 16) & 0xFF);
//                    int bb = ((color >>> 8)  & 0xFF);
//                    // We get a sub-random angle from 0-PI2 using the R2 sequence.
//                    // This gets us an angle theta from anywhere on the circle, which we feed into three
//                    // different cos() calls, each with a different offset to get 3 different angles.
//                    final float theta = ((px * 0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL >>> 41) * 0x1.921fb6p-21f); //0x1.921fb6p-21f is 0x1p-23f * MathUtils.PI2
//                    rr = Math.min(Math.max((int)(rr + MathUtils.cos(theta        ) * str + 0.5f), 0), 255);
//                    gg = Math.min(Math.max((int)(gg + MathUtils.cos(theta + 1.04f) * str + 0.5f), 0), 255);
//                    bb = Math.min(Math.max((int)(bb + MathUtils.cos(theta + 2.09f) * str + 0.5f), 0), 255);

                    final float theta = ((px * 0xC13FA9A9 + y * 0x91E10DA5 >>> 9) * 0x1p-23f);
                    int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + OtherMath.triangleWave(theta         ) * str)] & 255;
                    int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + OtherMath.triangleWave(theta + 0.209f) * str)] & 255;
                    int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + OtherMath.triangleWave(theta + 0.518f) * str)] & 255;
                    
                    pixmap.drawPixel(px, y, paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }
    /**
     * An intentionally low-fidelity dither, meant for pixel art.
     * @param pixmap
     * @return
     */
    public Pixmap reduceLoaf(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color;
        final float strength = Math.min(Math.max(2.5f + 5f * ditherStrength - 5.5f * populationBias, 0f), 7.9f);
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    int adj = (int)((((px + y & 1) << 5) - 16) * strength); // either + 16 * strength or - 16 * strength
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + adj, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + adj, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + adj, 0), 1023)] & 255;
                    int rgb555 = ((rr << 7) & 0x7C00) | ((gg << 2) & 0x3E0) | ((bb >>> 3));
                    pixmap.drawPixel(px, y, paletteArray[paletteMapping[rgb555] & 0xFF]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Primarily used to avoid allocating arrays that copy {@link #thresholdMatrix64}, this has length 64.
     */
    public static final float[] tempThresholdMatrix = new float[64];
    /**
     * A specialized lookup table that takes an index from 0-255 and outputs a float in the 127.5 to 894.5 range.
     * The output is expected to have a value between -128 and 128 added to it and that given to {@link #fromLinearLUT}.
     */
    public static final float[] toLinearLUT = new float[256];
    /**
     * A specialized lookup table that takes an index from 0-1023 and outputs a byte that should be masked with 255 (to
     * get a value from 0-255). The input is usually from {@link #toLinearLUT}, with a value in the -128-128 range
     * added, and this used to get back into the 0-255 range (with the mask).
     */
    public static final byte[] fromLinearLUT = new byte[1024];
    static {
        for (int i = 0; i < 256; i++) {
            float small = i / 255f;
            toLinearLUT[i] = (i <= 255f * 0.0031308f
                    ? small * (12.92f)
                    : (float) (1.055 * Math.pow(small, 1.0/2.4) - 0.055))
                    * 767 + 127.5f;
        }
        for (int i = 0; i < 1024; i++) {
            double small = Math.min(Math.max(i - 127.5, 0), 767) / 767.0;
            fromLinearLUT[i] = (byte) (int)((small <= 0.04045
                    ? small * (1.0 / 12.92)
                    : Math.pow((small + 0.055)/1.055, 2.4))
                    * 255 + 0.5);
        }
    }

    /**
     * A higher-quality relative of {@link #reduceLoaf(Pixmap)} that uses a 8x8 grid instead of a 2x2 checkerboard, and
     * that gamma-corrects its changes.
     * @param pixmap
     * @return
     */
    public Pixmap reduceGourd(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color;
//        final float strength = (ditherStrength * 6.75f * (float) Math.pow(OtherMath.cbrtPositive(OtherMath.logRough(colorCount)) * 0.5649f, -8f)); // probitF
//        final float strength = (ditherStrength * 6.75f * (float) Math.pow(populationBias, -4f)); // probitF
//        final float strength = (ditherStrength * 4f * populationBias); // none
//        final float strength = Math.min(ditherStrength * (8.75f - populationBias * 8f), 4f); // none
        // is the lowest possible populationBias^4, 0.8188650241570136f is the difference between the highest populationBias^4 and the lowest.
        final float strength = Math.min(ditherStrength * (4f - (populationBias * populationBias * populationBias * populationBias - 0.1598797460796939f) * (3.5f / 0.8188650241570136f)), 4f);
//        final float strength = Math.min(1.5f * ditherStrength / (populationBias * populationBias * populationBias), 4f);
//        final float strength = (float)(Math.min(Math.max(ditherStrength * 85 * Math.pow(populationBias, -8.0), -255), 255)); // triangularRemap
//        System.out.println("strength is " + strength + " when ditherStrength is "+ ditherStrength + " and colorCount is " + colorCount);
//        System.out.println("triangular remap is " + (float)(ditherStrength * 85 * Math.pow(populationBias, -8.0)));
        for (int i = 0; i < 64; i++) {
            tempThresholdMatrix[i] = Math.min(Math.max((PaletteReducer.thresholdMatrix64[i] - 31.5f) * strength, -127), 127);
//            tempThresholdMatrix[i] = Math.min(Math.max(OtherMath.probitF((PaletteReducer.thresholdMatrix64[i] + 0.5f) * 0x1p-6f) * strength, -127), 127);
//            tempThresholdMatrix[i] = (OtherMath.triangularRemap(PaletteReducer.thresholdMatrix64[i], 63) - 0.5f) * strength;
        }
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    int idx = (px & 7) ^ (y << 3 & 56);
                    
                    int rr = (fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + tempThresholdMatrix[idx ^ 0b101110])] & 255);
                    int gg = (fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + tempThresholdMatrix[idx ^ 0b110011])] & 255);
                    int bb = (fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + tempThresholdMatrix[idx ^ 0b100111])] & 255);
                    int rgb555 = ((rr << 7) & 0x7C00) | ((gg << 2) & 0x3E0) | ((bb >>> 3));
                    pixmap.drawPixel(px, y, paletteArray[paletteMapping[rgb555] & 0xFF]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    public Pixmap reduceWoven(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        final float w1 = (float) (10f * Math.sqrt(ditherStrength) / (populationBias * populationBias)), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = 100f * ditherStrength / (populationBias * populationBias * populationBias * populationBias),
                limit = 5f + 250f / (float)Math.sqrt(colorCount+1.5f);

        for (int y = 0; y < h; y++) {
            int ny = y + 1;

            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, lineLen);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, lineLen);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);

            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    er = Math.min(Math.max(((((px+1) * 0xC13FA9A902A6328FL + (y+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorRed[px]);
                    eg = Math.min(Math.max(((((px+3) * 0xC13FA9A902A6328FL + (y-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorGreen[px]);
                    eb = Math.min(Math.max(((((px-4) * 0xC13FA9A902A6328FL + (y+2) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorBlue[px]);

//                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
//                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
//                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);

                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;

                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (0x5p-10f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x5p-10f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x5p-10f * ((color>>>8&255)- (used>>>8&255)) );

                    if(px < lineLen - 1)
                    {
                        curErrorRed[px+1]   += rdiff * w7;
                        curErrorGreen[px+1] += gdiff * w7;
                        curErrorBlue[px+1]  += bdiff * w7;
                    }
                    if(ny < h)
                    {
                        if(px > 0)
                        {
                            nextErrorRed[px-1]   += rdiff * w3;
                            nextErrorGreen[px-1] += gdiff * w3;
                            nextErrorBlue[px-1]  += bdiff * w3;
                        }
                        if(px < lineLen - 1)
                        {
                            nextErrorRed[px+1]   += rdiff * w1;
                            nextErrorGreen[px+1] += gdiff * w1;
                            nextErrorBlue[px+1]  += bdiff * w1;
                        }
                        nextErrorRed[px]   += rdiff * w5;
                        nextErrorGreen[px] += gdiff * w5;
                        nextErrorBlue[px]  += bdiff * w5;
                    }
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Just as the wren flits restlessly from eave to branch to awning, so too does Wren dither dart to and fro between
     * dithering techniques. This is an error diffusion dither modeled closely after {@link #reduceWoven(Pixmap)}, which
     * means it uses three offset versions of the R2 sequence to introduce a structured artifact that breaks up
     * Floyd-Steinberg artifacts. It also incorporates per-channel blue-noise effects as {@link #reduceDodgy(Pixmap)}
     * uses them. The strengths of various components here have changed from the values used in Woven; the
     * error-diffusion strength is higher by default and the adjustment from blue noise and/or R2 values is
     * comparatively mild.
     * @param pixmap
     * @return
     */
    public Pixmap reduceWrenOriginal(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        final float w1 = (float) (32.0 * ditherStrength * (populationBias * populationBias)), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = (0.2f * ditherStrength / (populationBias * populationBias * populationBias * populationBias)),
                limit = 5f + 125f / (float)Math.sqrt(colorCount+1.5),
                dmul = 0x1p-8f;

        for (int y = 0; y < h; y++) {
            int ny = y + 1;

            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, lineLen);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, lineLen);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);

            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    er = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE  [(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+1) * 0xC13FA9A902A6328FL + (y +1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-16f - 0x1p+6f)) * strength) + (curErrorRed[px]), -limit), limit);
                    eg = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_B[(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+3) * 0xC13FA9A902A6328FL + (y -1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-16f - 0x1p+6f)) * strength) + (curErrorGreen[px]), -limit), limit);
                    eb = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_C[(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+2) * 0xC13FA9A902A6328FL + (y -4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-16f - 0x1p+6f)) * strength) + (curErrorBlue[px]), -limit), limit);

                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (dmul * ((color>>>24)-    (used>>>24))    );
                    gdiff = (dmul * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (dmul * ((color>>>8&255)- (used>>>8&255)) );

                    if(px < lineLen - 1)
                    {
                        curErrorRed[px+1]   += rdiff * w7;
                        curErrorGreen[px+1] += gdiff * w7;
                        curErrorBlue[px+1]  += bdiff * w7;
                    }
                    if(ny < h)
                    {
                        if(px > 0)
                        {
                            nextErrorRed[px-1]   += rdiff * w3;
                            nextErrorGreen[px-1] += gdiff * w3;
                            nextErrorBlue[px-1]  += bdiff * w3;
                        }
                        if(px < lineLen - 1)
                        {
                            nextErrorRed[px+1]   += rdiff * w1;
                            nextErrorGreen[px+1] += gdiff * w1;
                            nextErrorBlue[px+1]  += bdiff * w1;
                        }
                        nextErrorRed[px]   += rdiff * w5;
                        nextErrorGreen[px] += gdiff * w5;
                        nextErrorBlue[px]  += bdiff * w5;
                    }
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    public Pixmap reduceWren(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        float partialDitherStrength = (0.5f * ditherStrength / (populationBias * populationBias)),
                strength = (80f * ditherStrength / (populationBias * populationBias)),
                blueStrength = (0.3f * ditherStrength / (populationBias * populationBias)),
                limit = 5f + 200f / (float)Math.sqrt(colorCount+1.5f),
                r1, g1, b1, r2, g2, b2, r4, g4, b4;

        for (int y = 0; y < h; y++) {

            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, lineLen);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, lineLen);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);

            for (int x = 0; x < lineLen; x++) {
                color = pixmap.getPixel(x, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(x, y, 0);
                else {
                    er = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE  [(x & 63) | (y & 63) << 6] + 0.5f) * blueStrength + ((((x+1) * 0xC13FA9A902A6328FL + (y+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorRed[x]);
                    eg = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_B[(x & 63) | (y & 63) << 6] + 0.5f) * blueStrength + ((((x+3) * 0xC13FA9A902A6328FL + (y-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorGreen[x]);
                    eb = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_C[(x & 63) | (y & 63) << 6] + 0.5f) * blueStrength + ((((x+2) * 0xC13FA9A902A6328FL + (y-4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorBlue[x]);

                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(x, y, used);
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
                    if(x < lineLen - 1)
                    {
                        curErrorRed[x+1]   += r4;
                        curErrorGreen[x+1] += g4;
                        curErrorBlue[x+1]  += b4;
                        if(x < lineLen - 2)
                        {

                            curErrorRed[x+2]   += r2;
                            curErrorGreen[x+2] += g2;
                            curErrorBlue[x+2]  += b2;
                        }
                    }
                    if(y+1 < h)
                    {
                        if(x > 0)
                        {
                            nextErrorRed[x-1]   += r2;
                            nextErrorGreen[x-1] += g2;
                            nextErrorBlue[x-1]  += b2;
                            if(x > 1)
                            {
                                nextErrorRed[x-2]   += r1;
                                nextErrorGreen[x-2] += g1;
                                nextErrorBlue[x-2]  += b1;
                            }
                        }
                        nextErrorRed[x]   += r4;
                        nextErrorGreen[x] += g4;
                        nextErrorBlue[x]  += b4;
                        if(x < lineLen - 1)
                        {
                            nextErrorRed[x+1]   += r2;
                            nextErrorGreen[x+1] += g2;
                            nextErrorBlue[x+1]  += b2;
                            if(x < lineLen - 2)
                            {

                                nextErrorRed[x+2]   += r1;
                                nextErrorGreen[x+2] += g1;
                                nextErrorBlue[x+2]  += b1;
                            }
                        }
                    }
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * A blue-noise-based dither; does not diffuse error, and uses a tiling blue noise pattern (which can be accessed
     * with {@link #TRI_BLUE_NOISE}, but shouldn't usually be modified) as well as a checkerboard pattern of light and
     * dark. Because it is an
     * ordered dither, it avoids "swimming" patterns in animations with large flat sections of one color; these swimming
     * effects can appear in all the error-diffusion dithers here. If you can tolerate "spongy" artifacts appearing
     * (which look worse on small palettes) and the checkerboard doesn't distract too much, this may work OK.
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceBlueNoise (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color;
        float adj, strength = 0.3125f * ditherStrength / (populationBias * populationBias * populationBias);
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    adj = ((px + y & 1) << 8) - 127.5f;
                    int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE_B[(px & 63) | (y & 63) << 6] + adj) * strength), -100), 100))] & 255;
                    int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE_C[(px & 63) | (y & 63) << 6] + adj) * strength), -100), 100))] & 255;
                    int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE  [(px & 63) | (y & 63) << 6] + adj) * strength), -100), 100))] & 255;
//                    adj = ((PaletteReducer.TRI_BLUE_NOISE_B[(px & 63) | (y & 63) << 6] + 0.5f));
//                    adj = adj * strength / (12f + Math.abs(adj)) + 0.5f;
//                    int rr = Math.min(Math.max((int) (adj + ((color >>> 24)       )), 0), 255);
//                    adj = ((PaletteReducer.TRI_BLUE_NOISE_C[(px & 63) | (y & 63) << 6] + 0.5f));
//                    adj = adj * strength / (12f + Math.abs(adj)) + 0.5f;
//                    int gg = Math.min(Math.max((int) (adj + ((color >>> 16) & 0xFF)), 0), 255);
//                    adj = ((PaletteReducer.TRI_BLUE_NOISE  [(px & 63) | (y & 63) << 6] + 0.5f));
//                    adj = adj * strength / (12f + Math.abs(adj)) + 0.5f;
//                    int bb = Math.min(Math.max((int) (adj + ((color >>> 8)  & 0xFF)), 0), 255);

                    pixmap.drawPixel(px, y, paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * A white-noise-based dither; uses the colors encountered so far during dithering as a sort of state for basic
     * pseudo-random number generation, while also using some blue noise from a tiling texture to offset clumping.
     * This tends to be very rough-looking, and generally only looks good with larger palettes or with animations. It
     * could be a good aesthetic choice if you want a scratchy, "distressed-looking" image.
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceChaoticNoise (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        double adj, strength = ditherStrength * populationBias * 1.5;
        long s = 0xC13FA9A902A6328FL;
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    int rr = ((color >>> 24)       );
                    int gg = ((color >>> 16) & 0xFF);
                    int bb = ((color >>> 8)  & 0xFF);
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
                                    ((s = (s ^ rr + gg + bb) * 0xD1342543DE82EF95L + 0x91E10DA5C79E7B1DL) >> 15));
                    rr = Math.min(Math.max((int) (rr + (adj * ((rr - (used >>> 24))))), 0), 0xFF);
                    gg = Math.min(Math.max((int) (gg + (adj * ((gg - (used >>> 16 & 0xFF))))), 0), 0xFF);
                    bb = Math.min(Math.max((int) (bb + (adj * ((bb - (used >>> 8 & 0xFF))))), 0), 0xFF);
                    pixmap.drawPixel(px, y, paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Modifies the given Pixmap so it only uses colors present in this PaletteReducer, using Floyd-Steinberg to dither
     * but modifying patterns slightly by introducing triangular-distributed blue noise. If you want to reduce the
     * colors in a Pixmap based on what it currently contains, call {@link #analyze(Pixmap)} with {@code pixmap} as its
     * argument, then call this method with the same Pixmap. You may instead want to use a known palette instead of one
     * computed from a Pixmap; {@link #exact(int[])} is the tool for that job.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduceScatter (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        final float w1 = Math.min(ditherStrength * 5.5f / populationBias, 16f), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;
        for (int y = 0; y < h; y++) {
            int ny = y + 1;

            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, lineLen);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, lineLen);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);

            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    float tbn = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS[(px & 63) | ((y << 6) & 0xFC0)];
                    er = curErrorRed[px] * tbn;
                    eg = curErrorGreen[px] * tbn;
                    eb = curErrorBlue[px] * tbn;
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (0x2.Ep-8f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x2.Ep-8f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x2.Ep-8f * ((color>>>8&255)- (used>>>8&255)) );
                    rdiff *= 1.25f / (0.25f + Math.abs(rdiff));
                    gdiff *= 1.25f / (0.25f + Math.abs(gdiff));
                    bdiff *= 1.25f / (0.25f + Math.abs(bdiff));
                    if(px < lineLen - 1)
                    {
                        curErrorRed[px+1]   += rdiff * w7;
                        curErrorGreen[px+1] += gdiff * w7;
                        curErrorBlue[px+1]  += bdiff * w7;
                    }
                    if(ny < h)
                    {
                        if(px > 0)
                        {
                            nextErrorRed[px-1]   += rdiff * w3;
                            nextErrorGreen[px-1] += gdiff * w3;
                            nextErrorBlue[px-1]  += bdiff * w3;
                        }
                        if(px < lineLen - 1)
                        {
                            nextErrorRed[px+1]   += rdiff * w1;
                            nextErrorGreen[px+1] += gdiff * w1;
                            nextErrorBlue[px+1]  += bdiff * w1;
                        }
                        nextErrorRed[px]   += rdiff * w5;
                        nextErrorGreen[px] += gdiff * w5;
                        nextErrorBlue[px]  += bdiff * w5;
                    }
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * An error-diffusion dither based on {@link #reduceFloydSteinberg(Pixmap)}, but adding in triangular-mapped blue
     * noise before diffusing, like {@link #reduceBlueNoise(Pixmap)}. This looks like {@link #reduceScatter(Pixmap)} in
     * many cases, but smooth gradients are much smoother with Neue than Scatter. Scatter multiplies error by a blue
     * noise value, where this adds blue noise regardless of error. This also preserves color better than TrueBlue,
     * while keeping similar gradient smoothness. The algorithm here uses a 2x2 rough checkerboard pattern to offset
     * some roughness that can appear in blue noise; the checkerboard can appear in some cases when a dithered image is
     * zoomed with certain image filters.
     * <br>
     * Neue is a German word for "new," and this is a new look at Scatter's technique.
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceNeue(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb, adj;
        byte paletteIndex;
        final float w1 = ditherStrength * 8f, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = (70f * ditherStrength / (populationBias * populationBias * populationBias)),
                limit = Math.min(127, (float) Math.pow(80, 1.635 - populationBias));

        for (int py = 0; py < h; py++) {
            int ny = py + 1;

            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, lineLen);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, lineLen);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);

            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, py, 0);
                else {
                    adj = ((TRI_BLUE_NOISE[(px & 63) | (py & 63) << 6] + 0.5f) * 0.005f); // plus or minus 255/400
                    adj = Math.min(Math.max(adj * strength, -limit), limit);
                    er = adj + (curErrorRed[px]);
                    eg = adj + (curErrorGreen[px]);
                    eb = adj + (curErrorBlue[px]);
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, py, used);
                    rdiff = (0x2.Ep-8f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x2.Ep-8f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x2.Ep-8f * ((color>>>8&255)- (used>>>8&255)) );
                    rdiff *= 1.25f / (0.25f + Math.abs(rdiff));
                    gdiff *= 1.25f / (0.25f + Math.abs(gdiff));
                    bdiff *= 1.25f / (0.25f + Math.abs(bdiff));
                    if(px < lineLen - 1)
                    {
                        curErrorRed[px+1]   += rdiff * w7;
                        curErrorGreen[px+1] += gdiff * w7;
                        curErrorBlue[px+1]  += bdiff * w7;
                    }
                    if(ny < h)
                    {
                        if(px > 0)
                        {
                            nextErrorRed[px-1]   += rdiff * w3;
                            nextErrorGreen[px-1] += gdiff * w3;
                            nextErrorBlue[px-1]  += bdiff * w3;
                        }
                        if(px < lineLen - 1)
                        {
                            nextErrorRed[px+1]   += rdiff * w1;
                            nextErrorGreen[px+1] += gdiff * w1;
                            nextErrorBlue[px+1]  += bdiff * w1;
                        }
                        nextErrorRed[px]   += rdiff * w5;
                        nextErrorGreen[px] += gdiff * w5;
                        nextErrorBlue[px]  += bdiff * w5;
                    }
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * An error-diffusion dither that adds in error based on blue noise, much like {@link #reduceNeue(Pixmap)}, but
     * unlike Neue it adds different blue noise values in for each RGB channel. This tends to improve color accuracy
     * quite a bit, but does add some random-seeming noise from how the different noise textures aren't connected. For
     * some palettes, this may very well be the best dither here. It has different color quality when compared to
     * {@link #reduceWoven(Pixmap)}, and is sometimes better, while this dither lacks the repetitive artifacts in Woven.
     * <br>
     * This dither uses blue noise, and it's baseball season in America; my local LA Dodgers have blue as their color.
     *
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceDodgy(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        final float w1 = 8f * ditherStrength,
                w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = 0.35f * ditherStrength / (populationBias * populationBias * populationBias),
                limit = 90f;

        for (int py = 0; py < h; py++) {
            int ny = py + 1;

            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, lineLen);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, lineLen);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);

            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, py, 0);
                else {
                    er = Math.min(Math.max(((TRI_BLUE_NOISE  [(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorRed[px]);
                    eg = Math.min(Math.max(((TRI_BLUE_NOISE_B[(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorGreen[px]);
                    eb = Math.min(Math.max(((TRI_BLUE_NOISE_C[(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorBlue[px]);
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, py, used);

                    rdiff = (0x5p-8f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x5p-8f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x5p-8f * ((color>>>8&255)- (used>>>8&255)) );
                    rdiff /= (0.5f + Math.abs(rdiff));
                    gdiff /= (0.5f + Math.abs(gdiff));
                    bdiff /= (0.5f + Math.abs(bdiff));

                    if(px < lineLen - 1)
                    {
                        curErrorRed[px+1]   += rdiff * w7;
                        curErrorGreen[px+1] += gdiff * w7;
                        curErrorBlue[px+1]  += bdiff * w7;
                    }
                    if(ny < h)
                    {
                        if(px > 0)
                        {
                            nextErrorRed[px-1]   += rdiff * w3;
                            nextErrorGreen[px-1] += gdiff * w3;
                            nextErrorBlue[px-1]  += bdiff * w3;
                        }
                        if(px < lineLen - 1)
                        {
                            nextErrorRed[px+1]   += rdiff * w1;
                            nextErrorGreen[px+1] += gdiff * w1;
                            nextErrorBlue[px+1]  += bdiff * w1;
                        }
                        nextErrorRed[px]   += rdiff * w5;
                        nextErrorGreen[px] += gdiff * w5;
                        nextErrorBlue[px]  += bdiff * w5;
                    }
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Burkes error diffusion dither with some extra error added in, selecting different types of error pattern in an
     * ordered way. This incorporates two types of extra error to each channel of each pixel, selecting based on a grid
     * of 2x2 pixel squares. Error applies differently to each RGB channel. The types of extra error are:
     * <ul>
     * <li>An R2 dither value (as used by {@link #reduceRoberts(Pixmap)}) is used for each pixel, but the four corners
     * of the 2x2 square each use a different angle for the artifacts.</li>
     * <li>Blue noise from {@link #TRI_BLUE_NOISE} is incorporated into two corners, with different strength.</li>
     * <li>XOR-Mod patterns are incorporated when blue noise isn't. They consist of diagonal lines. These are:
     * <ul>
     *     <li>{@code ((px ^ y) % 9 - 4)}</li>
     *     <li>{@code ((px ^ y) % 11 - 5)}</li>
     * </ul>
     * </li>
     * </ul>
     * <br>
     * This is called Overboard because it is probably going overboard with the different types of extra error. Just
     * Burkes dither on its own is probably good enough. The results can look quite good, though, and tend to be
     * slightly smoother than {@link Dithered.DitherAlgorithm#WREN}. This also looks better than WREN when the dither
     * strength is higher than 1.0 and the color count is high.
     *
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceOverboard(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        final float strength = ditherStrength * 0.5f * (populationBias * populationBias),
                noiseStrength = 2f / (populationBias),
                limit = 5f + 125f / (float)Math.sqrt(colorCount+1.5f);

        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < h; y++) {
            int ny = y + 1;

            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, lineLen);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, lineLen);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);

            for (int x = 0; x < lineLen; x++) {
                int color = pixmap.getPixel(x, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(x, y, 0);
                else {
                    float er = 0f, eg = 0f, eb = 0f;
                    switch ((x << 1 & 2) | (y & 1)){
                        case 0:
                            er += ((x ^ y) % 9 - 4);
                            er += ((x * 0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                            eg += (TRI_BLUE_NOISE_B[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-5f;
                            eg += ((x * -0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                            eb += (TRI_BLUE_NOISE_C[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-6f;
                            eb += ((y * 0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-20f;
                            break;
                        case 1:
                            er += (TRI_BLUE_NOISE[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-5f;
                            er += ((x * -0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                            eg += (TRI_BLUE_NOISE_B[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-6f;
                            eg += ((y * 0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-20f;
                            eb += ((x ^ y) % 11 - 5);
                            eb += ((y * -0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-21f;
                            break;
                        case 2:
                            er += (TRI_BLUE_NOISE[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-6f;
                            er += ((y * 0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-20f;
                            eg += ((x ^ y) % 11 - 5);
                            eg += ((y * -0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-21f;
                            eb += ((x ^ y) % 9 - 4);
                            eb += ((x * 0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                            break;
                        default: // case 3:
                            er += ((x ^ y) % 11 - 5);
                            er += ((y * -0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-21f;
                            eg += ((x ^ y) % 9 - 4);
                            eg += ((x * 0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                            eb += (TRI_BLUE_NOISE_C[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-5f;
                            eb += ((x * -0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                            break;
                    }
                    er = er * noiseStrength + curErrorRed[x];
                    eg = eg * noiseStrength + curErrorGreen[x];
                    eb = eb * noiseStrength + curErrorBlue[x];
                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + Math.min(Math.max(er, -limit), limit) + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + Math.min(Math.max(eg, -limit), limit) + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + Math.min(Math.max(eb, -limit), limit) + 0.5f), 0), 0xFF);
                    byte paletteIndex = paletteMapping[((rr << 7) & 0x7C00)
                                                       | ((gg << 2) & 0x3E0)
                                                       | ((bb >>> 3))];
                    int used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(x, y, used);
                    float rdiff = ((color >>> 24) - (used >>> 24)) * strength;
                    float gdiff = ((color >>> 16 & 255) - (used >>> 16 & 255)) * strength;
                    float bdiff = ((color >>> 8 & 255) - (used >>> 8 & 255)) * strength;
                    float r1 = rdiff * 16f / (45f + Math.abs(rdiff));
                    float g1 = gdiff * 16f / (45f + Math.abs(gdiff));
                    float b1 = bdiff * 16f / (45f + Math.abs(bdiff));
//                    float r1 = rdiff * 16f / (float)Math.sqrt(2048f + rdiff * rdiff);
//                    float g1 = gdiff * 16f / (float)Math.sqrt(2048f + gdiff * gdiff);
//                    float b1 = bdiff * 16f / (float)Math.sqrt(2048f + bdiff * bdiff);
                    float r2 = r1 + r1;
                    float g2 = g1 + g1;
                    float b2 = b1 + b1;
                    float r4 = r2 + r2;
                    float g4 = g2 + g2;
                    float b4 = b2 + b2;
                    if(x < lineLen - 1)
                    {
                        curErrorRed[x+1]   += r4;
                        curErrorGreen[x+1] += g4;
                        curErrorBlue[x+1]  += b4;
                        if(x < lineLen - 2)
                        {

                            curErrorRed[x+2]   += r2;
                            curErrorGreen[x+2] += g2;
                            curErrorBlue[x+2]  += b2;
                        }
                    }
                    if(ny < h)
                    {
                        if(x > 0)
                        {
                            nextErrorRed[x-1]   += r2;
                            nextErrorGreen[x-1] += g2;
                            nextErrorBlue[x-1]  += b2;
                            if(x > 1)
                            {
                                nextErrorRed[x-2]   += r1;
                                nextErrorGreen[x-2] += g1;
                                nextErrorBlue[x-2]  += b1;
                            }
                        }
                        nextErrorRed[x]   += r4;
                        nextErrorGreen[x] += g4;
                        nextErrorBlue[x]  += b4;
                        if(x < lineLen - 1)
                        {
                            nextErrorRed[x+1]   += r2;
                            nextErrorGreen[x+1] += g2;
                            nextErrorBlue[x+1]  += b2;
                            if(x < lineLen - 2)
                            {

                                nextErrorRed[x+2]   += r1;
                                nextErrorGreen[x+2] += g1;
                                nextErrorBlue[x+2]  += b1;
                            }
                        }
                    }
                }
            }

        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Modifies the given Pixmap so that it only uses colors present in this PaletteReducer, dithering when it can
     * with Burkes dithering, a type of error-diffusion dither. This method looks, surprisingly, quite a lot better
     * than Floyd-Steinberg dithering, despite being similar in most regards.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduceBurkes (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float r4, r2, r1, g4, g2, g1, b4, b2, b1;
        final float s = 0.175f * ditherStrength * (populationBias * populationBias * populationBias),
                strength = s * 0.29f / (0.19f + s);
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        for (int py = 0; py < h; py++) {
            int ny = py + 1;

            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, lineLen);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, lineLen);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, lineLen);

            Arrays.fill(nextErrorRed, 0, lineLen, 0);
            Arrays.fill(nextErrorGreen, 0, lineLen, 0);
            Arrays.fill(nextErrorBlue, 0, lineLen, 0);

            for (int px = 0; px < lineLen; px++) {
                int color = pixmap.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, py, 0);
                else {
                    float er = curErrorRed[px];
                    float eg = curErrorGreen[px];
                    float eb = curErrorBlue[px];
                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                    byte paletteIndex = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))];
                    int used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, py, used);
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
                    if(px < lineLen - 1)
                    {
                        curErrorRed[px+1]   += r4;
                        curErrorGreen[px+1] += g4;
                        curErrorBlue[px+1]  += b4;
                        if(px < lineLen - 2)
                        {
                            curErrorRed[px+2]   += r2;
                            curErrorGreen[px+2] += g2;
                            curErrorBlue[px+2]  += b2;
                        }
                    }
                    if(ny < h)
                    {
                        if(px > 0)
                        {
                            nextErrorRed[px-1]   += r2;
                            nextErrorGreen[px-1] += g2;
                            nextErrorBlue[px-1]  += b2;
                            if(px > 1)
                            {
                                nextErrorRed[px-2]   += r1;
                                nextErrorGreen[px-2] += g1;
                                nextErrorBlue[px-2]  += b1;
                            }
                        }
                        nextErrorRed[px]   += r4;
                        nextErrorGreen[px] += g4;
                        nextErrorBlue[px]  += b4;
                        if(px < lineLen - 1)
                        {
                            nextErrorRed[px+1]   += r2;
                            nextErrorGreen[px+1] += g2;
                            nextErrorBlue[px+1]  += b2;
                            if(px < lineLen - 2)
                            {
                                nextErrorRed[px+2]   += r1;
                                nextErrorGreen[px+2] += g1;
                                nextErrorBlue[px+2]  += b1;
                            }
                        }
                    }
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * A variant on {@link #reduceBurkes(Pixmap)} that multiplies the diffused error per-pixel using
     * {@link #TRI_BLUE_NOISE_MULTIPLIERS}. This does a good job of breaking up artifacts in sections
     * of flat color, where with Burkes, there could be ugly repetitive areas with seams.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduceOceanic (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int w = pixmap.getWidth(), h = pixmap.getHeight();
        final float[] noise = TRI_BLUE_NOISE_MULTIPLIERS;
        float r4, r2, r1, g4, g2, g1, b4, b2, b1;
        final float s = 0.175f * ditherStrength * (populationBias * populationBias * populationBias),
                strength = s * 0.29f / (0.19f + s);
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(w)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(w)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(w)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(w)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(w)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(w)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(w);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(w);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(w);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(w);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(w);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(w);

            Arrays.fill(nextErrorRed, 0, w, 0);
            Arrays.fill(nextErrorGreen, 0, w, 0);
            Arrays.fill(nextErrorBlue, 0, w, 0);
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used, rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        for (int py = 0; py < h; py++) {
            int ny = py + 1;

            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0, w, 0);
            Arrays.fill(nextErrorGreen, 0, w, 0);
            Arrays.fill(nextErrorBlue, 0, w, 0);

            for (int px = 0; px < w; px++) {
                color = pixmap.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, py, 0);
                else {
                    er = curErrorRed[px];
                    eg = curErrorGreen[px];
                    eb = curErrorBlue[px];
                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);

                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, py, used);
                    rdiff = (color>>>24)-    (used>>>24);
                    gdiff = (color>>>16&255)-(used>>>16&255);
                    bdiff = (color>>>8&255)- (used>>>8&255);
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
                    if(px < w - 1)
                    {
                        modifier = noise[(px + 1 & 63) | ((py << 6) & 0xFC0)];
                        curErrorRed[px+1]   += r4 * modifier;
                        curErrorGreen[px+1] += g4 * modifier;
                        curErrorBlue[px+1]  += b4 * modifier;
                        if(px < w - 2)
                        {
                            modifier = noise[(px + 2 & 63) | ((py << 6) & 0xFC0)];
                            curErrorRed[px+2]   += r2 * modifier;
                            curErrorGreen[px+2] += g2 * modifier;
                            curErrorBlue[px+2]  += b2 * modifier;
                        }
                    }
                    if(ny < h)
                    {
                        if(px > 0)
                        {
                            modifier = noise[(px - 1 & 63) | ((ny << 6) & 0xFC0)];
                            nextErrorRed[px-1]   += r2 * modifier;
                            nextErrorGreen[px-1] += g2 * modifier;
                            nextErrorBlue[px-1]  += b2 * modifier;
                            if(px > 1)
                            {
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
                        if(px < w - 1)
                        {
                            modifier = noise[(px + 1 & 63) | ((ny << 6) & 0xFC0)];
                            nextErrorRed[px+1]   += r2 * modifier;
                            nextErrorGreen[px+1] += g2 * modifier;
                            nextErrorBlue[px+1]  += b2 * modifier;
                            if(px < w - 2)
                            {
                                modifier = noise[(px + 2 & 63) | ((ny << 6) & 0xFC0)];
                                nextErrorRed[px+2]   += r1 * modifier;
                                nextErrorGreen[px+2] += g1 * modifier;
                                nextErrorBlue[px+2]  += b1 * modifier;
                            }
                        }
                    }
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * A variant on {@link #reduceOceanic(Pixmap)} (and thus on {@link #reduceBurkes(Pixmap)}) that uses
     * different blue noise effects per-channel, which can improve color quality at a minor speed cost.
     * This also makes an unorthodox change to the Burkes error diffusion pattern by diffusing a small amount of error
     * 3 pixels to the right. This is meant to break up some fine horizontal band artifacts.
     *
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduceSeaside (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int w = pixmap.getWidth(), h = pixmap.getHeight();
        final float[] noiseA = TRI_BLUE_NOISE_MULTIPLIERS;
        final float[] noiseB = TRI_BLUE_NOISE_MULTIPLIERS_B;
        final float[] noiseC = TRI_BLUE_NOISE_MULTIPLIERS_C;
        final float s = (0.13f * ditherStrength * (populationBias * populationBias)),
                strength = s * 0.29f / (0.18f + s);
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(w)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(w)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(w)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(w)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(w)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(w)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(w);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(w);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(w);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(w);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(w);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(w);

            Arrays.fill(nextErrorRed, 0, w, 0);
            Arrays.fill(nextErrorGreen, 0, w, 0);
            Arrays.fill(nextErrorBlue, 0, w, 0);
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used, rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        for (int py = 0; py < h; py++) {
            int ny = py + 1;

            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0, w, 0);
            Arrays.fill(nextErrorGreen, 0, w, 0);
            Arrays.fill(nextErrorBlue, 0, w, 0);

            for (int px = 0; px < w; px++) {
                color = pixmap.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, py, 0);
                else {
                    er = curErrorRed[px];
                    eg = curErrorGreen[px];
                    eb = curErrorBlue[px];
                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);

                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, py, used);
                    rdiff = (color>>>24)-    (used>>>24);
                    gdiff = (color>>>16&255)-(used>>>16&255);
                    bdiff = (color>>>8&255)- (used>>>8&255);
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

                    if(px < w - 1)
                    {
                        modifier = ((px + 1 & 63) | (py << 6 & 0xFC0));
                        curErrorRed[px+1]   += r4 * noiseA[modifier];
                        curErrorGreen[px+1] += g4 * noiseB[modifier];
                        curErrorBlue[px+1]  += b4 * noiseC[modifier];
                        if(px < w - 2)
                        {
                            modifier = ((px + 2 & 63) | ((py << 6) & 0xFC0));
                            curErrorRed[px+2]   += r2 * noiseA[modifier];
                            curErrorGreen[px+2] += g2 * noiseB[modifier];
                            curErrorBlue[px+2]  += b2 * noiseC[modifier];
                        }
                        if(px < w - 3)
                        {
                            modifier = ((px + 3 & 63) | ((py << 6) & 0xFC0));
                            curErrorRed[px+2]   += r1 * noiseA[modifier];
                            curErrorGreen[px+2] += g1 * noiseB[modifier];
                            curErrorBlue[px+2]  += b1 * noiseC[modifier];
                        }
                    }
                    if(ny < h)
                    {
                        if(px > 0)
                        {
                            modifier = (px - 1 & 63) | ((ny << 6) & 0xFC0);
                            nextErrorRed[px-1]   += r2 * noiseA[modifier];
                            nextErrorGreen[px-1] += g2 * noiseB[modifier];
                            nextErrorBlue[px-1]  += b2 * noiseC[modifier];
                            if(px > 1)
                            {
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
                        if(px < w - 1)
                        {
                            modifier = (px + 1 & 63) | ((ny << 6) & 0xFC0);
                            nextErrorRed[px+1]   += r2 * noiseA[modifier];
                            nextErrorGreen[px+1] += g2 * noiseB[modifier];
                            nextErrorBlue[px+1]  += b2 * noiseC[modifier];
                            if(px < w - 2)
                            {
                                modifier = (px + 2 & 63) | ((ny << 6) & 0xFC0);
                                nextErrorRed[px+2]   += r1 * noiseA[modifier];
                                nextErrorGreen[px+2] += g1 * noiseB[modifier];
                                nextErrorBlue[px+2]  += b1 * noiseC[modifier];
                            }
                        }
                    }
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Compares items in ints by their luma, looking up items by the indices a and b, and swaps the two given indices if
     * the item at a has higher luma than the item at b. This requires items to be present as two ints per item: the
     * earlier int, at an index less than 16, is what gets sorted, while the later int, at an index 16 higher than the
     * earlier one, is an RGB555 int.
     * <br>
     * This is protected rather than private because it's more likely
     * that this would be desirable to override than a method that uses it, like {@link #reduceKnoll(Pixmap)}. Uses
     * {@link #OKLAB} to look up accurate luma for the given RGB555 colors in the later half of {@code ints}.
     * @param ints an int array than must be able to take a, b, a+16, and b+16 as indices; may be modified in place
     * @param a an index into ints
     * @param b an index into ints
     */
    protected static void compareSwap(final int[] ints, final int a, final int b) {
        if(OKLAB[0][ints[a|16]] > OKLAB[0][ints[b|16]]) {
            final int t = ints[a], st = ints[a|16];
            ints[a] = ints[b];
            ints[a|16] = ints[b|16];
            ints[b] = t;
            ints[b|16] = st;
        }
    }
    
    /**
     * Sorting network, found by http://pages.ripco.net/~jgamble/nw.html , considered the best known for length 8.
     * @param i8 an 8-or-more-element array that will be sorted in-place by {@link #compareSwap(int[], int, int)}
     */
    static void sort8(final int[] i8) {
        compareSwap(i8, 0, 1);
        compareSwap(i8, 2, 3);
        compareSwap(i8, 0, 2);
        compareSwap(i8, 1, 3);
        compareSwap(i8, 1, 2);
        compareSwap(i8, 4, 5);
        compareSwap(i8, 6, 7);
        compareSwap(i8, 4, 6);
        compareSwap(i8, 5, 7);
        compareSwap(i8, 5, 6);
        compareSwap(i8, 0, 4);
        compareSwap(i8, 1, 5);
        compareSwap(i8, 1, 4);
        compareSwap(i8, 2, 6);
        compareSwap(i8, 3, 7);
        compareSwap(i8, 3, 6);
        compareSwap(i8, 2, 4);
        compareSwap(i8, 3, 5);
        compareSwap(i8, 3, 4);
    }
    /**
     * Sorting network, found by http://pages.ripco.net/~jgamble/nw.html , considered the best known for length 16.
     * @param i16 a 16-element array that will be sorted in-place by {@link #compareSwap(int[], int, int)}
     */
    static void sort16(final int[] i16)
    {
        compareSwap(i16, 0, 1);
        compareSwap(i16, 2, 3);
        compareSwap(i16, 4, 5);
        compareSwap(i16, 6, 7);
        compareSwap(i16, 8, 9);
        compareSwap(i16, 10, 11);
        compareSwap(i16, 12, 13);
        compareSwap(i16, 14, 15);
        compareSwap(i16, 0, 2);
        compareSwap(i16, 4, 6);
        compareSwap(i16, 8, 10);
        compareSwap(i16, 12, 14);
        compareSwap(i16, 1, 3);
        compareSwap(i16, 5, 7);
        compareSwap(i16, 9, 11);
        compareSwap(i16, 13, 15);
        compareSwap(i16, 0, 4);
        compareSwap(i16, 8, 12);
        compareSwap(i16, 1, 5);
        compareSwap(i16, 9, 13);
        compareSwap(i16, 2, 6);
        compareSwap(i16, 10, 14);
        compareSwap(i16, 3, 7);
        compareSwap(i16, 11, 15);
        compareSwap(i16, 0, 8);
        compareSwap(i16, 1, 9);
        compareSwap(i16, 2, 10);
        compareSwap(i16, 3, 11);
        compareSwap(i16, 4, 12);
        compareSwap(i16, 5, 13);
        compareSwap(i16, 6, 14);
        compareSwap(i16, 7, 15);
        compareSwap(i16, 5, 10);
        compareSwap(i16, 6, 9);
        compareSwap(i16, 3, 12);
        compareSwap(i16, 13, 14);
        compareSwap(i16, 7, 11);
        compareSwap(i16, 1, 2);
        compareSwap(i16, 4, 8);
        compareSwap(i16, 1, 4);
        compareSwap(i16, 7, 13);
        compareSwap(i16, 2, 8);
        compareSwap(i16, 11, 14);
        compareSwap(i16, 2, 4);
        compareSwap(i16, 5, 6);
        compareSwap(i16, 9, 10);
        compareSwap(i16, 11, 13);
        compareSwap(i16, 3, 8);
        compareSwap(i16, 7, 12);
        compareSwap(i16, 6, 8);
        compareSwap(i16, 10, 12);
        compareSwap(i16, 3, 5);
        compareSwap(i16, 7, 9);
        compareSwap(i16, 3, 4);
        compareSwap(i16, 5, 6);
        compareSwap(i16, 7, 8);
        compareSwap(i16, 9, 10);
        compareSwap(i16, 11, 12);
        compareSwap(i16, 6, 7);
        compareSwap(i16, 8, 9);
    }

    /**
     * Reduces a Pixmap to the palette this knows by using Thomas Knoll's pattern dither, which is out-of-patent since
     * late 2019. The output this produces is very dependent on the palette and this PaletteReducer's dither strength,
     * which can be set with {@link #setDitherStrength(float)}. At close-up zooms, a strong grid pattern will be visible
     * on most dithered output (like needlepoint). The algorithm was described in detail by Joel Yliluoma in
     * <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">this dithering article</a>. Yliluoma used an 8x8
     * threshold matrix because at the time 4x4 was still covered by the patent, but using 4x4 allows a much faster
     * sorting step (this uses a sorting network, which works well for small input sizes like 16 items). This is still
     * very significantly slower than the other dithers here (although {@link #reduceKnollRoberts(Pixmap)} isn't at all
     * fast, it still takes less than half the time this method does).
     * <br>
     * Using pattern dither tends to produce some of the best results for lightness-based gradients, but when viewed
     * close-up the "needlepoint" pattern can be jarring for images that should look natural.
     * @see #reduceKnollRoberts(Pixmap) An alternative that uses a similar pattern but skews it to obscure the grid
     * @param pixmap a Pixmap that will be modified
     * @return {@code pixmap}, after modifications
     */
    public Pixmap reduceKnoll (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used, cr, cg, cb, usedIndex;
        final float errorMul = (ditherStrength * 0.5f / populationBias);
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    int er = 0, eg = 0, eb = 0;
                    cr = (color >>> 24);
                    cg = (color >>> 16 & 0xFF);
                    cb = (color >>> 8 & 0xFF);
                    for (int i = 0; i < 16; i++) {
                        int rr = Math.min(Math.max((int) (cr + er * errorMul), 0), 255);
                        int gg = Math.min(Math.max((int) (cg + eg * errorMul), 0), 255);
                        int bb = Math.min(Math.max((int) (cb + eb * errorMul), 0), 255);
                        usedIndex = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))] & 0xFF;
                        candidates[i | 16] = shrink(candidates[i] = used = paletteArray[usedIndex]);
                        er += cr - (used >>> 24);
                        eg += cg - (used >>> 16 & 0xFF);
                        eb += cb - (used >>> 8 & 0xFF);
                    }
                    sort16(candidates);
                    pixmap.drawPixel(px, y, candidates[thresholdMatrix16[((px & 3) | (y & 3) << 2)]]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Reduces a Pixmap to the palette this knows by using a skewed version of Thomas Knoll's pattern dither, which is
     * out-of-patent since late 2019, using the harmonious numbers rediscovered by Martin Roberts to handle the skew.
     * The output this produces is very dependent on the palette and this PaletteReducer's dither strength, which can be
     * set with {@link #setDitherStrength(float)}. A hexagonal pattern can be visible on many outputs this produces;
     * this artifact can be mitigated by lowering dither strength. The algorithm was described in detail by Joel
     * Yliluoma in <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">this dithering article</a>. Yliluoma used an
     * 8x8 threshold matrix because at the time 4x4 was still covered by the patent, but using 4x4 allows a much faster
     * sorting step (this uses a sorting network, which works well for small input sizes like 16 items). This is stil
     * very significantly slower than the other dithers here (except for {@link #reduceKnoll(Pixmap)}.
     * <br>
     * While the original Knoll pattern dither has square-shaped "needlepoint" artifacts, this has a varying-size
     * hexagonal or triangular pattern of dots that it uses to dither. Much like how Simplex noise uses a triangular
     * lattice to improve the natural feeling of noise relative to earlier Perlin noise and its square lattice, the
     * skew here makes the artifacts usually less-noticeable.
     * @see #reduceKnoll(Pixmap) An alternative that uses a similar pattern but has a more obvious grid
     * @param pixmap a Pixmap that will be modified
     * @return {@code pixmap}, after modifications
     */
    public Pixmap reduceKnollRoberts (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used, cr, cg, cb, usedIndex;
        final float errorMul = ditherStrength * populationBias * 1.25f;
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    pixmap.drawPixel(px, y, 0);
                else {
                    int er = 0, eg = 0, eb = 0;
                    cr = (color >>> 24);
                    cg = (color >>> 16 & 0xFF);
                    cb = (color >>> 8 & 0xFF);
                    for (int c = 0; c < 8; c++) {
                        int rr = Math.min(Math.max((int) (cr + er * errorMul), 0), 255);
                        int gg = Math.min(Math.max((int) (cg + eg * errorMul), 0), 255);
                        int bb = Math.min(Math.max((int) (cb + eb * errorMul), 0), 255);
                        usedIndex = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))] & 0xFF;
                        candidates[c | 16] = shrink(candidates[c] = used = paletteArray[usedIndex]);
                        er += cr - (used >>> 24);
                        eg += cg - (used >>> 16 & 0xFF);
                        eb += cb - (used >>> 8 & 0xFF);
                    }
                    sort8(candidates);
                    pixmap.drawPixel(px, y, candidates[thresholdMatrix8[
                            ((int) (px * 0x1.C13FA9A902A6328Fp3 + y * 0x1.9E3779B97F4A7C15p-2) & 3) ^
                                    ((px & 3) | (y & 1) << 2)
                            ]]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }


    /**
     * Retrieves a random non-0 color index for the palette this would reduce to, with a higher likelihood for colors
     * that are used more often in reductions (those with few similar colors). The index is returned as a byte that,
     * when masked with 255 as with {@code (palette.randomColorIndex(random) & 255)}, can be used as an index into a
     * palette array with 256 or fewer elements that should have been used with {@link #exact(int[])} before to set the
     * palette this uses.
     * @param random a Random instance, which may be seeded
     * @return a randomly selected color index from this palette with a non-uniform distribution, can be any byte but 0
     */
    public byte randomColorIndex(Random random)
    {
        return paletteMapping[random.nextInt() >>> 17];
    }

    /**
     * Retrieves a random non-transparent color from the palette this would reduce to, with a higher likelihood for
     * colors that are used more often in reductions (those with few similar colors). The color is returned as an
     * RGBA8888 int; you can assign one of these into a Color with {@link Color#rgba8888ToColor(Color, int)} or
     * {@link Color#set(int)}.
     * @param random a Random instance, which may be seeded
     * @return a randomly selected RGBA8888 color from this palette with a non-uniform distribution
     */
    public int randomColor(Random random)
    {
        return paletteArray[paletteMapping[random.nextInt() >>> 17] & 255];
    }

    /**
     * Looks up {@code color} as if it was part of an image being color-reduced and finds the closest color to it in the
     * palette this holds. Both the parameter and the returned color are RGBA8888 ints.
     * @param color an RGBA8888 int that represents a color this should try to find a similar color for in its palette
     * @return an RGBA8888 int representing a color from this palette, or 0 if color is mostly transparent
     * (0 is often but not always in the palette)
     */
    public int reduceSingle(int color)
    {
        if((color & 0x80) == 0) // less visible than half-transparent
            return 0; // transparent
        return paletteArray[paletteMapping[
                (color >>> 17 & 0x7C00)
                        | (color >>> 14 & 0x3E0)
                        | (color >>> 11 & 0x1F)] & 0xFF];
    }

    /**
     * Looks up {@code color} as if it was part of an image being color-reduced and finds the closest color to it in the
     * palette this holds. The parameter is a RGBA8888 int, the returned color is a byte index into the
     * {@link #paletteArray} (mask it like: {@code paletteArray[reduceIndex(color) & 0xFF]}).
     * @param color an RGBA8888 int that represents a color this should try to find a similar color for in its palette
     * @return a byte index that can be used to look up a color from the {@link #paletteArray}
     */
    public byte reduceIndex(int color)
    {
        if((color & 0x80) == 0) // less visible than half-transparent
            return 0; // transparent
        return paletteMapping[
                (color >>> 17 & 0x7C00)
                        | (color >>> 14 & 0x3E0)
                        | (color >>> 11 & 0x1F)];
    }

    /**
     * Looks up {@code color} as if it was part of an image being color-reduced and finds the closest color to it in the
     * palette this holds. Both the parameter and the returned color are packed float colors, as produced by
     * {@link Color#toFloatBits()} or many methods in SColor.
     * @param packedColor a packed float color this should try to find a similar color for in its palette
     * @return a packed float color from this palette, or 0f if color is mostly transparent
     * (0f is often but not always in the palette)
     */
    public float reduceFloat(float packedColor)
    {
        final int color = NumberUtils.floatToIntBits(packedColor);
        if(color >= 0) // if color is non-negative, then alpha is less than half of opaque
            return 0f;
        return NumberUtils.intBitsToFloat(Integer.reverseBytes(paletteArray[paletteMapping[
                (color << 7 & 0x7C00)
                        | (color >>> 6 & 0x3E0)
                        | (color >>> 19)] & 0xFF] & 0xFFFFFFFE));

    }

    /**
     * Modifies {@code color} so its RGB values will match the closest color in this PaletteReducer's palette. If color
     * has {@link Color#a} less than 0.5f, this will simply set color to be fully transparent, with rgba all 0.
     * @param color a libGDX Color that will be modified in-place; do not use a Color constant, use {@link Color#cpy()}
     *              or a temporary Color
     * @return color, after modifications.
     */
    public Color reduceInPlace(Color color)
    {
        if(color.a < 0.5f)
            return color.set(0);
        return color.set(paletteArray[paletteMapping[
                ((int) (color.r * 0x1f.8p+10) & 0x7C00)
                        | ((int) (color.g * 0x1f.8p+5) & 0x3E0)
                        | ((int) (color.r * 0x1f.8p+0))] & 0xFF]);
    }

    /**
     * Edits this PaletteReducer by changing each used color in the Oklab color space with an {@link Interpolation}.
     * This allows adjusting lightness, such as for gamma correction. You could use {@link Interpolation#pow2InInverse}
     * to use the square root of a color's lightness instead of its actual lightness, or {@link Interpolation#pow2In} to
     * square the lightness instead.
     * @param lightness an Interpolation that will affect the lightness of each color
     * @return this PaletteReducer, for chaining
     */
    public PaletteReducer alterColorsLightness(Interpolation lightness) {
        int[] palette = paletteArray;
        for (int idx = 0; idx < colorCount; idx++) {
            int s = shrink(palette[idx]);
            palette[idx] = oklabToRGB(lightness.apply(OKLAB[0][s]), OKLAB[1][s], OKLAB[2][s],
                    (palette[idx] & 0xFE) / 254f);
        }
        return this;
    }

    /**
     * Edits this PaletteReducer by changing each used color in the Oklab color space with an {@link Interpolation}.
     * This allows adjusting lightness, such as for gamma correction, but also individually emphasizing or
     * de-emphasizing different aspects of the chroma. You could use {@link Interpolation#pow2InInverse} to use the
     * square root of a color's lightness instead of its actual lightness (which, because lightness is in the 0 to 1
     * range, always results in a color with the same lightness or a higher lightness), or {@link Interpolation#pow2In}
     * to square the lightness instead (this always results in a color with the same or lower lightness). You could make
     * colors more saturated by passing {@link Interpolation#circle} to greenToRed and blueToYellow, or get a
     * less-extreme version by using {@link Interpolation#smooth}. To desaturate colors is a different task; you can
     * create a {@link OtherMath.BiasGain} Interpolation with 0.5 turning and maybe 0.25 to 0.75 shape to produce
     * different strengths of desaturation. Using a shape of 1.5 to 4 with BiasGain is another way to saturate the
     * colors.
     * @param lightness an Interpolation that will affect the lightness of each color
     * @param greenToRed an Interpolation that will make colors more green if it evaluates below 0.5 or more red otherwise
     * @param blueToYellow an Interpolation that will make colors more blue if it evaluates below 0.5 or more yellow otherwise
     * @return this PaletteReducer, for chaining
     */
    public PaletteReducer alterColorsOklab(Interpolation lightness, Interpolation greenToRed, Interpolation blueToYellow) {
        int[] palette = paletteArray;
        for (int idx = 0; idx < colorCount; idx++) {
            int s = shrink(palette[idx]);
            float L = lightness.apply(OKLAB[0][s]);
            float A = greenToRed.apply(-1, 1, OKLAB[1][s] * 0.5f + 0.5f);
            float B = blueToYellow.apply(-1, 1, OKLAB[2][s] * 0.5f + 0.5f);
            palette[idx] = oklabToRGB(L, A, B, (palette[idx] & 0xFE) / 254f);
        }
        return this;
    }

    /**
     * Edits this PaletteReducer by changing each used color so lighter colors lean towards warmer hues, while darker
     * colors lean toward cooler or more purple-ish hues.
     * @return this PaletteReducer, for chaining
     */
    public PaletteReducer hueShift() {
        int[] palette = paletteArray;
        for (int idx = 0; idx < colorCount; idx++) {
            int s = shrink(palette[idx]);
            float L = OKLAB[0][s];
            float A = OKLAB[1][s] + (L - 0.5f) * 0.04f;
            float B = OKLAB[2][s] + (L - 0.5f) * 0.08f;
            palette[idx] = oklabToRGB(L, A, B, (palette[idx] & 0xFE) / 254f);
        }
        return this;
    }


}
