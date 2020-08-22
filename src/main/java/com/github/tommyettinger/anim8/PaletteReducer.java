package com.github.tommyettinger.anim8;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ByteArray;
import com.badlogic.gdx.utils.IntIntMap;
import com.badlogic.gdx.utils.NumberUtils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

/**
 * Data that can be used to limit the colors present in a Pixmap or other image, here with the goal of using 256 or less
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
 *     <li>{@link #reduceJimenez(Pixmap)} (This is a modified version of Gradient Interleaved Noise by Jorge Jimenez;
 *     it's a kind of ordered dither that introduces a subtle wave pattern to break up solid blocks. It does quite well
 *     on some animations and on smooth or rounded shapes.)</li>
 *     <li>{@link #reduceKnollRoberts(Pixmap)} (This is a modified version of Thomas Knoll's Pattern Dithering; it skews
 *     a grid-based ordered dither and also does a small amount of gamma correction, so lightness may change. It
 *     preserves shape extremely well, but is almost never faithful to the original colors.)</li>
 *     <li>{@link #reduceChaoticNoise(Pixmap)} (Uses blue noise and pseudo-random white noise, with a carefully chosen
 *     distribution, to disturb what would otherwise be flat bands. This does introduce chaotic or static-looking
 *     pixels, but with larger palettes they won't be far from the original.)</li>
 *     </ul>
 *     </li>
 *     <li>OTHER TIER
 *     <ul>
 *     <li>{@link #reduceBlueNoise(Pixmap)} (Uses a blue noise texture, which has almost no apparent patterns, to adjust
 *     the amount of color correction applied to each mismatched pixel; also uses a quasi-random pattern. This may not
 *     add enough disruption to some images, which leads to a flat-looking result.)</li>
 *     <li>{@link #reduceSierraLite(Pixmap)} (Like Floyd-Steinberg, Sierra Lite is an error-diffusion dither, and it
 *     sometimes looks better than Floyd-Steinberg, but usually is similar or worse unless the palette is small. If
 *     Floyd-Steinberg has unexpected artifacts, you can try Sierra Lite, and it may avoid those issues.)</li>
 *     <li>{@link #reduceKnoll(Pixmap)} (Thomas Knoll's Pattern Dithering, used more or less verbatim except for the
 *     inclusion of some gamma correction; this version has a heavy grid pattern that looks like an artifact. The skew
 *     applied to Knoll-Roberts gives it a more subtle triangular grid, while the square grid here is a bit bad.)</li>
 *     <li>{@link #reduceSolid(Pixmap)} (No dither! Solid colors! Mostly useful when you want to preserve blocky parts
 *     of a source image, or for some kinds of pixel/low-color art.)</li>
 *     </ul>
 *     </li>
 * </ul>
 * <p>
 * Created by Tommy Ettinger on 6/23/2018.
 */
public class PaletteReducer {
    private static final int[] AURORA = {
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
     * Converts an RGBA8888 int color to the RGB555 format used by {@link #LAB} to look up colors.
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
     * Stores CIE LAB components corresponding to RGB555 indices.
     * LAB[0] stores lightness from 0.0 to 100.0 .
     * LAB[1] stores CIE A, which is something like a green-red axis, from roughly -128.0 (green) to 128.0 (red).
     * LAB[2] stores CIE B, which is something like a blue-yellow axis, from roughly -128.0 (blue) to 128.0 (yellow).
     * <br>
     * The indices into each of these double[] values store red in bits 10-14, green in bits 5-9, and blue in bits 0-4.
     * It's ideal to work with these indices with bitwise operations, as with {@code (r << 10 | g << 5 | b)}, where r,
     * g, and b are all in the 0-31 range inclusive. It's usually easiest to convert an RGBA8888 int color to an RGB555
     * color with {@link #shrink(int)}.
     */
    public static final double[][] LAB = new double[3][0x8000];
    
    public static final double[] HUE = new double[0x8000];
    /**
     * Used by {@link #difference(int, int)} and its overloads, this stores precalculated exponents for all of the 256
     * possible differences between color channel values. For how difference() uses this, it needs different powers for
     * each channel. This stores all red differences (their absolute values, specifically) first, then all green, then
     * all blue. To get a red difference's power value, look up its absolute value in this; to get a green one or a blue
     * one, it's the same but with an added 256 or 512.
     */
    public static final double[] RGB_POWERS = new double[256];

    /**
     * This should always be a 4096-element byte array filled with 64 sections of 64 bytes each. When arranged into a
     * grid, the bytes will follow a blue noise distribution. This is public and non-final (blame Android), so you could
     * change the contents to some other distribution of bytes or even assign a different array here, but you should
     * never let the length be less than 4096, or let this become null.
     */
    public static byte[] RAW_BLUE_NOISE;

    /**
     * Altered-range approximation of the frequently-used trigonometric method atan2, taking y and x positions as 
     * doubles and returning an angle measured in turns from 0.0 to 1.0 (inclusive), with one cycle over the range
     * equivalent to 360 degrees or 2PI radians. You can multiply the angle by {@code 6.2831855f} to change to radians,
     * or by {@code 360f} to change to degrees. Takes y and x (in that unusual order) as doubles. Will never return a
     * negative number, which may help avoid costly floating-point modulus when you actually want a positive number.
     * Credit to StackExchange user njuffa, who gave
     * <a href="https://math.stackexchange.com/a/1105038">this useful answer</a>. Note that
     * {@link Math#atan2(double, double)} returns an angle in radians and can return negative results, which may be fine
     * for many tasks; this method is much faster than Math's version on JDK 8 and earlier, and a little faster on later
     * JDK versions.
     * @param y y-component of the point to find the angle towards; note the parameter order is unusual by convention
     * @param x x-component of the point to find the angle towards; note the parameter order is unusual by convention
     * @return the angle to the given point, as a double from 0.0 to 1.0, inclusive
     */
    public static double atan2_(final double y, final double x)
    {
        if(y == 0.0 && x >= 0.0) return 0.0;
        final double ax = Math.abs(x), ay = Math.abs(y);
        if(ax < ay)
        {
            final double a = ax / ay, s = a * a,
                    r = 0.25 - (((-0.0464964749 * s + 0.15931422) * s - 0.327622764) * s * a + a) * 0.15915494309189535;
            return (x < 0.0) ? (y < 0.0) ? 0.5 + r : 0.5 - r : (y < 0.0) ? 1.0 - r : r;
        }
        else {
            final double a = ay / ax, s = a * a,
                    r = (((-0.0464964749 * s + 0.15931422) * s - 0.327622764) * s * a + a) * 0.15915494309189535;
            return (x < 0.0) ? (y < 0.0) ? 0.5 + r : 0.5 - r : (y < 0.0) ? 1.0 - r : r;
        }
    }

    static {
        try {
            RAW_BLUE_NOISE = "ÁwK1¶\025à\007ú¾íNY\030çzÎúdÓi ­rì¨ýÝI£g;~O\023×\006vE1`»Ü\004)±7\fº%LÓD\0377ÜE*\fÿí\177£RÏA2\r(Å\0026\023¯?*Â;ÌE!Â\022,è\006ºá6h\"ó¢Én\"<sZÅAt×\022\002x,aèkZõl±×\033dÅ&k°Ö÷nCÚ]%é\177ø\022S\001Øl´uÉ\036þ«À>Zß\000O®ñ\021Õæe÷¨ê^Â±\030þ®\021¹?èUªE6è\023_|¼¢!­t½P\005ÙG¥¸u.\030ò>Tÿ3nXCvíp*³\033ìÑyC¼/\031P1;òSÝÈ2KÒ\"È3r Óø·V\000\034ä4\bVê\020õgÇ\0331êÞ`¯ÅeãÓ­ò×\rÈ\034KÏ\013h5\tÃ\037T\002~Í´ kÐq@~ïc\003x\023ó»\005OxÛÃJÎeIÒ7´p]\013#J\006 $`F¿¡*³`åôS½F¤bùÝl¦Há\rû¡æ\013%º\005\035à©G[âc\020§=,mñµ=þÃ-\034å\ròM¿?Ïöq9¹\017xæ\032eù2¦\026:~Ùå-:¶ð'Ww¿KcªÕ\\¢OÀ-Ð³:¥+Éî!\\Ñ\f$qß}¦WB*«Õýz¨\025ìPÌ\0027|ÞRq\001Ä¬%ÿr¯\030Ò\016_Ç3Ö=\0260úè8\roøa\007Ù}ýAs¼áû¬Tè\024²_\007øÊxe\036µ1VØ(ª@ÚUÊ\007»Óaî\021WÆM{B\033s\005®óÉyiÍ¯\032ê%M\030±Nh\0267{Â¢K9Ö¹\026à:\tjæ¿~]÷h.µ\024J\"óC-\032KkÏ=ò\003é«Ûö»b\"ßU\b·B#ÞTpÀhèÔ2\tÊFÙ\003+Íñ lGa\000ÁQìË¢\033D\004\035Ãð¤pé®\\ Ýµ2º¡b)¿6kNëFl§\035Mÿ|1È?úª\017GZ÷£ì¶\037p[\017ä1¤&s-`û7±Òt\rYÑ9z\016Éêvü\tã\034pÖJ\007£*\017Å6×íÂ\023óµ\026.]Ì$q¹\034x-bVãø¼«wÃî³\020ÙH¸vÞP\022é3MÞ>\000Á*úeA\"ZD®û\037ÉYÔ\177µ\002t.f«\\JÖuÝ\003¡òß>Ô\f¨\0223B\002RÐ?[÷©\013#Æo[ü¹\"¬d\030á¸Q\0344ÂªÕ}Ç\017ç`xñ2¬ü`è\026XÑ9å\tïR´e4U­\003l¿<NÑhÝ#ù}\030Æm;ÐWô«)¢Í}ñoG¦Ó\003hð'V<µà\024D!Ë=÷º\037jÃ9\036AÁw\020ÈLúé\177\036´_\r¨ÜO,æ|\016?ÛjE\0076×S\nôxâT\022I6»\003Ò\031Oq¿Ûn Mà\020zFþ)§~â\013ùÖ*ë Ü7(Æ¡õ.ê¾i7·\004ë\036fF»\000Àï\027^µ&Ê1?¾,´ùÜn¦v+Å¢\0008õ±(Ã^¢Ø³VÎ¹Ni¨]z¶d\030F\005WuËL)åt¬ÂüØ4b\035T/¯æÄÿvê®b\036\brÍ\033éFöa\016ée\031W\nv\0020eô\024\001-Ë\031G»õ¢\nÐ«r×:\025\000Ñ\\B\fU\024±Ñ ygM\033\023Øí[©:dP\n±>Õ'¸Ðå;ªïÊ\034çpCÚaït2ÿn>RäZð%¸â£°y\034ò¢1Îz&îqãGû\017Ø,§BYý\177LBà\000½$Íjá»TªsI/f\026R@½4Å£\020²ãØÆ\027-ýÁ5\fHh÷V?ÄláH§[8\n·È;óÏlãÂ5Ï¹&\024{ò0\025}\005ÇòþÀØw\016\\­Py\036=X\t$¯Ak^Æ#\016¼Û(\022ù¹\005Á÷f!Uqº\t1³ ¡\006pöÇXÚ=] ù\"8Þb\035|L&µâúÒ$\006öÒ¿J}9dívÛ\022°ç\000ÔrìcI­X;n\032ÚO.¬ß¤\031_÷R^Ü/²E\013s­\003ÆêL\020C¯ê\tY£-gßl/`ýç§ÌW\004¹IÍ\037}Q/<¥\004~Õë$wÌ\023ë|\004JíyØA\025ë¨gSé\032&m³Òv½Ö3Ípð9ÄA·ì«'\024ö\032(¦ù2¾\032ß_¶Ì\0310Æ^³\000>ZÑ)Á8Ë&­iÇ:\036Ìü5¾ÔAW/¤[\002m\025¨@\003y\031M\017UÉFsÕf3ÁàQp[ïC«\007õPåüi\rIåg¼õC°ýNå\013ÿu¶\râw£cPàö\t\031äò%O_¼!Ú¯èÏ\177\034\007¹L°x;\bÅÚ\017iÏx$s8»B¢ò,\027¨4\034k`\022t£\\¾.ÖóN?)´\016{Ài>±Åúæ\016Hkaþ4Ýøá;ï\t\"Îèb®%7KÂ\021ÓY¬\037Ý|ÁPÕs\fãÙ¹ ñGV#Â]\001ð 4¬FÌ\177Ü/uÓô(<½¤%²_-Ziý\027G{¹æý+°î\006nÎ\004büÈM+ó?Ð4Ý{\027¥l®Þ\024ÏmIÇÚ]þ\035\bg;¥R¶ÇX\023gÅApÌ\023¼ÚA¥·1ò\003V\035`ÜoDeâN÷1W±à&E\177¬\007oX\003²çÍû2~;§çr\023é+Qºï\"ü\026|\006ãNð\r¡Oÿ|)ÈTuÜÒÇ§\0265'Å\017\033<£\022·í\\Á\030§Æi=c\bDí!W»*\004=¶¡Òc¨\021ÊCÝ2ªÓvýÙë\035­äù\036Kk\021<tPôÎ½\001®y@¹évôk2Ò\0369âJú-\021½&M·Õù\fgRöyHâvW²j\\ëG\036¸/©Tk1Hc9ê\006Á¬'ì¶/\f\177\\Úñ¤gÓÆHþfÔzëUóÚ\034È_r Ë­×\0360Ä\005>(ô\004¡Àqb<\024½Ò\boµ\025Ò\\ãbþAä L9\024Qÿ,[\bÝr\017´V$¯E Îw©k\020æ-M7\030ãnXì\027Ö½5\032Î+\017ôßË']@óÄ%¨}0DÌ\032m×¬f\bÌo$ß¯\035½«N5Çö\fÞ`3\0048Hþ°ÀyðCÿ¸jª[oOzÛ=S\006}çù°x6ßY\001@ºõ\nJ¼)XÄí´úÀCc7æzñ'çt@Àm\026¶\"áÃ¢#Ú\006(Å\0166IÎü\"\016è§ûi±Â \020Ú ¤MíhÜ\037uïÞ\0027\031w0^\rÊ\005T\023Ñ^¦.üïQù]}îVeµ\\¥~Ý+ä²Ç@_¸Jí\"6FnQcÀ\fs\031Ê,¡T±3[¦z\020MÔS¬ê~öiÖ?ÃlE\005\034ÖYÊuB¬Ô+\017<Í\026õKÑ\037øTwh\006(\024ÊuÓµ*þÔ²Iq\004ÕÅ\025?Íõ£#à\0265¸'¨\033ú°ßº~K®9'£\t\032¸kã¨v2æ=d¼\007:\032ñ1Ód©Ý\032\0024ÉâDïf8û¾\022ê<gû%¶ç_¿r\001FÏK\n]5$òä\020j½êÛb5È\003R¼\np¯ë\024Ë¦ÙQ¾\177ãøVNò¥_\023~«&á^O©áSl,\f=´f YÜîoÉçwV\reÈ\002OzðF\"ùBÛ\034ÄWHmaÿ®F :\b-¿x >º\007Y\027¡xÏ.\035~ÁD\002ª×íÅûv¼,E\003À£<*X\033qÏ,²\026^rÐ+b¢\001$Ø/ûå(\016!oÆ²jèÜøÏ.GÙÆö?\b·ó\rÑu\0338øR|1\017®9\025¦Ù\037MÒý¬Ùõ¶A\022üT×ç¬\r³|ð;xªÃCsÐì\027×D\f®Shêu±hÞI^5ëYÈi\025§ÕåcÑSeµ3ðmEz3â§m¿(9ÄQäHÎ^·\013\033Yº0MZþ%aÄs\033\000¥Q7\032T)Én­ú#·¢á»%@L\006$êù\017t\031â\013Â `LÉ;\nyîd\002\030.¿\020÷Lï7Üùfâ|ï6\021ã´È(\013¼å\000î\025ÙfA\021/K\001Zôm¶Ä\177>Ê¨Y¸d#ñ\005\037ë[øI\034¤×g«s ÙjÊ{\025©\b@Î¸¦L+B_ñcÐ©{:»,RÄ\004wÐîo¯Ë\035Û¤G0^Þ)\0018Îp·~.Û²Ì2ºtú@æ£=+\004³I'Às4\035\002Òõ|Ø2r!H\\\007rçª\\7\ny.ÿ\025ä¯\t¾PéI­0TÕ¤\024f\004àY;ÇS\007(ZýÀRâaÔé¡÷oXh\006»¢\020NßøÃ\021Óã¢\034Jõ'¾\033ÝÀëU:qÑg ÷{Õi\021üà\030CôÂO!n\016$®îÎ´z\022§p ôRe\fDÞÂ«8\"WÍ²> j4°ý=Í\177\rØlRúG\026a¸NïD\030¥Ä\\s\n:©ëEØ\177\0274fÞEìÌ9º\021,È³%ë\023Häuÿ\032í&Yh·5>³gªÓø\016Æ)¦6Í¶1ò@y»7ËaäþÓx·öa¾Jö\0350]\013C}«Û8wOýÇmó.¼]}ØGÂð-\000`¤îÎ\0060æ>#|ß[ÿu\013WmÛ&\004ì\035²X)\027É0O\001åp\tÄÔ°lÞûX\004\031òÑ`Ü\027­aB\nË§\001\035\020«ßÈo\022%{¾m J°\031¾ã!\000°ÐU¤h0H¿d\t¤Ñ)­9wS\001KÄ&èg¾¥3\177¸Q(ÖåS6¶kæuVC\036JàU\032Ê\005ì<×cFî Kd\024>Üó}\021áAí8v³Z@\024dè$½\027/u²Ï=o\013!@\002¡Ãyú\024NÏ )ø¿[­þBñÚ^2Ãj-\025³~ÇætÁ\013Ð¥\003¯nÝ\037ðÊûÙ¡óc¥î\017P\036ù×Vèdð0j\016\"FfÛ,õ<\005²×y\013.Ôq´*¦v\017õN©ûÔ'\n6øª*E\034lQ#ÆU\022¼fR¸3NË?Ø6`¹G­É\025Òá±ïº©wÅ\\\027ëH£ê5b\023Oü¶!Ìm=^ºPÖ`þ´5õÕ,üI\b}'j\003\023z¯\bl\000Þ{+\005K·Z<qË4]\036\fáo½4dËj\033»\006Èå:ÕUáz\b¾àñq\030yÞÃèbCz ç«;ÁäªÕ äEÉ(§ñÂ8k$ôþ\027QÔûB#ý©:öP}#nÁfB3ìW\037J¥/\005É9ïY'\n¸\031_Î!\026G÷8^¾T÷t\021Zä£\n|Å+¨ç\007|¡V°ðLÝ\021'á°AÜô®\f\033¥\001±\022Êÿ´ÚgS!¬\023Ih©Û\0013MtôÙ[rÇén¤0\031µLÍ?\035rÖW@ÚdJ*Æ6kÐ~UÁs]\rÍ+ZEé+bÙq¡e4xé¼Ô\002 Ì:nîÄ²\013.\007µ)\006Øeé\003³ü½3ì°\020tÀó³\021å\001\032¹/Ô\000\037í¹tÿÐ{KÆö%?æÃ&D\016r>{ø\036X&âj½<¦Q\027Dñ\177Ã:Û-cHj\037Sú#ÖD[a<\bóD¤m8\030¨5»\026·Ð\tQ\031]ü¦ñaä/¿PÓ\177B\030LûÑ{ßÌ¯Z\020#pS©î\027Ï§âË_;oª!wÊß©i´*L¿àU\007ðPà\n_2X{ô±ÖË*µU\025®ç\016÷¥Écì\037Xýi5ã¦öº\tÇ{\005".getBytes("ISO-8859-1");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        double r, g, b, x, y, z;
        int idx = 0;
        for (int ri = 0; ri < 32; ri++) {
            r = ri / 31.0;
            r = ((r > 0.04045) ? Math.pow((r + 0.055) / 1.055, 2.4) : r / 12.92);
            for (int gi = 0; gi < 32; gi++) {
                g = gi / 31.0;
                g = ((g > 0.04045) ? Math.pow((g + 0.055) / 1.055, 2.4) : g / 12.92);
                for (int bi = 0; bi < 32; bi++) {
                    b = bi / 31.0;
                    b = ((b > 0.04045) ? Math.pow((b + 0.055) / 1.055, 2.4) : b / 12.92);

                    x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.950489; // 0.96422;
                    y = (r * 0.2126 + g * 0.7152 + b * 0.0722) / 1.000000; // 1.00000;
                    z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.088840; // 0.82521;

                    x = (x > 0.008856) ? Math.cbrt(x) : (7.787037037037037 * x) + 0.13793103448275862;
                    y = (y > 0.008856) ? Math.cbrt(y) : (7.787037037037037 * y) + 0.13793103448275862;
                    z = (z > 0.008856) ? Math.cbrt(z) : (7.787037037037037 * z) + 0.13793103448275862;

                    LAB[0][idx] = (116.0 * y) - 16.0;
                    HUE[idx] = atan2_(LAB[1][idx] = 500.0 * (x - y), LAB[2][idx] = 200.0 * (y - z));
                    idx++;
                }
            }
        }

        for (int i = 1; i < 256; i++) {
            RGB_POWERS[i]     = Math.pow(i * 0.75, 3.5);
            //RGB_POWERS[i+256] = Math.pow(i * 0.25, 4.0);
            //RGB_POWERS[i+512] = Math.pow(i * 0.25, 3.3);
        }
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
     * it, but it is strongly encouraged that you instead call {@link #exact(int[])} when you want to change the colors
     * available, and treat this array as read-only. If you don't call exact() or {@link #analyze(Pixmap)} to set the
     * values in this, then the reductions that use gamma correction ({@link #reduceKnoll(Pixmap)},
     * {@link #reduceKnollRoberts(Pixmap)}, and any {@link Dithered} using
     * {@link com.github.tommyettinger.anim8.Dithered.DitherAlgorithm#PATTERN}) will be incorrect.
     */
    public final int[] paletteArray = new int[256];
    final int[] gammaArray = new int[256];
    ByteArray curErrorRedBytes, nextErrorRedBytes, curErrorGreenBytes, nextErrorGreenBytes, curErrorBlueBytes, nextErrorBlueBytes;
    float ditherStrength = 0.5f, halfDitherStrength = 0.25f;

    /**
     * This stores a preload code for a PaletteReducer using {@link #AURORA} with a simple metric. Using
     * a preload code in the constructor {@link #PaletteReducer(int[], byte[])} eliminates the time needed to fill 32 KB
     * of palette mapping in a somewhat-intricate way that only gets more intricate with better metrics, and replaces it
     * with a straightforward load from a String into a 32KB byte array. This load is a simple getBytes(), but does use
     * {@link StandardCharsets}, which requires Android API Level 19 or higher (Android OS 4.4 KitKat, present on over
     * 98% of devices) and is otherwise omnipresent. StandardCharsets is available on GWT and supports the charset used
     * here, ISO 8859-1, but doesn't support many other charsets (just UTF-8).
     */
    private static final byte[] ENCODED_AURORA = "\001\001\001\001\001\001\030\030\030\030\030\030\027\027\027\027\027\027\027\027ÞÞÞÞÞÞÞ\025\025\025\025\025\001\001\001\001\002\002u\030\030\030\030\030\030\027\027\027\027\027\027\027ÞÞÞÞÞÞÞ\025\025\025\025\025\001\001\002uuuuu\030\030\030\030\030\030\027\027\027\027\027ÞÞÞÞÞÞÞÞÝÝÝ\025\025\002\002\002uuuuààààà\030\030\030\030\027\027ÞÞÞÞÞÞÞÞÝÝÝÝÝÝ\002\002\002uuuuàààààààÊËËËËÞÞÞÞÞÝÝÝÝÝÝÝÝWWuuuuàààààààËËËËËËËËÝÝÝÝÝÝÝÝÝÝÝW½½½½½½½½àààËËËËËËËËËËÝÝÝÝÝÝÝÝÝÝ³½½½½½½½½½ÊÊËËËËËËËÌÌÌÌÌÌÌÎÎÎÎÎÎ³½½½½½½½½½½ËËËËËËÌÌÌÌÌÌÌÌÎÎÎÎÎÎÎ´´´´´½½½ÉÉÉÉÉÉÉÌÌÌÌÌÌÌÌÌÌÎÎÎÎÎÎÎ´´´´´´´ÉÉÉÉÉÉÉÉÉÌÌÌÌÌÌÌÌÌÎÎÎÎÎÎÎ´´´´´´ffÉÉÉÉÉÉÉÉÌÌÌÌÌÌÌÌÏÎÎÎÎÎÎÎ´´´´´´fffÉÉÉÉÉÉÉÉÉÏÏÏÏÏÏÏÏÏÎÎÎÎÎ´´´´´fffffffÉÉÉ\020\020\020\020\020ÏÏÏÏÏÏÏÏ××××+++++fffffff\020\020\020\020\020\020\020\020ÏÏÏÏÏÏÏ×××××+++++fffffff\020\020\020\020\020\020\020\020\020ÏÏÏÏÏ××××××++++++¼¼¼¼¼\020\020\020\020\020\020\020\020\020\020\020\020ÈÈÈ××××××+++++µ¼¼¼¼¼\020\020\020\020\020\020\020\020\020\020\020ÈÈÈÈÈÈ××××µµµµµµ¼¼¼¼¼¾¾¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµµµ¼¼¼¼¾¾¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµ»»»»»»»»¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµººº»»»»»»»»»»»¾¾¾¾¿¿¿ÈÈÈÈÈÈÈÖÖÖºººººº»»»»»»»»»¿¿¿¿¿¿¿¿¿ÈÖÖÖÖÖÖÖººººººº»»»»»»»¿¿¿¿¿¿¿¿¿¿¿ÖÖÖÖÖÖÖººººººº»»»»»»»¿¿¿¿¿¿¿¿¿¿ÇÇÖÖÖÖÖÖ¶ºººººººÀÀÀÀÀÀÀ¿¿¿¿¿¿¿¿ÇÇÇÇÇÇÖÖÖ¶¶¶¶¶¶ººÀÀÀÀÀÀÀÀÀ¿¿¿¿¿ÇÇÇÇÇÇÇÇÇÇ¶¶¶¶¶¶¶¶ÀÀÀÀÀÀÀÀÀÀÀÀÇÇÇÇÇÇÇÇÇÇÇÇ·······ÀÀÀÀÀÀÀÀÀÀÀÀÀÇÇÇÇÇÇÇÇÇÇÇÇ--······ÀÀÀÀÀÀÀÀÀÀÀÀÇÇÇÇÇÇÇÇÇ\022\022\022------··ÀÀÀÀÀÀÀÀÀÀÀÀÇÇÇÇÇ\022\022\022\022\022\022\022---------ÀÀÀÀÀÀÀÀÀÀÁÁÇÇ\022\022\022\022\022\022\022\022\022\001\001\001\001\001\002\030\030\030\030\030\030\027\027\027\027\027\027\027\027ÞÞÞÞÞÞÞ\025\025\025\025\025\001\001\001\002\002\002u\030\030\030\030\030\030\027\027\027\027\027\027\027ÞÞÞÞÞÞÞ\025\025\025\025\025\002\002\002\002uuuu\030\030\030\030\030\030\027\027\027\027\027ÞÞÞÞÞÞÞÝÝÝÝ\025\025\002\002\002\002uuuuàà\030\030\030\030\030ßßßßÞÞÞÞÞÞÝÝÝÝÝÝÝWWWuuuuàààààÊÊÊËËËËÞÞÞÞÝÝÝÝÝÝÝÝÝWWWWW½ààààààÊËËËËËËËËÝÝÝÝÝÝÝÝÝÝÝWgg½½½½½½ÊÊÊÊËËËËËËËËËÝÝÝÝÝÝÝÝÝÝ³³½½½½½½½½ÊÊËËËËËËËËÌÌÌÌÌÌÎÎÎÎÎÎ³³½½½½½½½½½ËËËËËËÌÌÌÌÌÌÌÌÎÎÎÎÎÎÎ´´´´½½½½ÉÉÉÉÉÉÉÌÌÌÌÌÌÌÌÌÌÎÎÎÎÎÎÎ´´´´´´´ÉÉÉÉÉÉÉÉÉÌÌÌÌÌÌÌÌÌÎÎÎÎÎÎÎ´´´´´´ffÉÉÉÉÉÉÉÉÌÌÌÌÌÌÏÏÏÏÎÎÎÎÎÎ´´´´´´fffffÉÉÉÉÉÉÉÏÏÏÏÏÏÏÏÏÎÎÎÎÎ´´´´´ffffffffÉÉ\020\020\020\020ÏÏÏÏÏÏÏÏÏ××××+++++ffffffff\020\020\020\020\020\020\020ÏÏÏÏÏÏÏ×××××+++++fffffff\020\020\020\020\020\020\020\020ÏÏÏÏÏÏÏ×××××++++++¼¼¼¼¼¼\020\020\020\020\020\020\020\020\020\020ÐÐÐÐ××××××µµµµµµ¼¼¼¼¼¼¼¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈ××××µµµµµµ¼¼¼¼¼¼¾¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµµ¼¼¼¼¼¼¾¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµµ»»»»»»»¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµ»»»»»»»»»»»¾¾¾¾¾¿¿ÈÈÈÈÈÈÈÈÖÖºººººº»»»»»»»»»¿¿¿¿¿¿¿¿¿ÈÖÖÖÖÖÖÖººººººº»»»»»»»¿¿¿¿¿¿¿¿¿¿¿ÖÖÖÖÖÖÖººººººº»»»»»»»¿¿¿¿¿¿¿¿¿¿ÇÇÖÖÖÖÖÖ¶¶¶ºººººÀÀÀÀÀÀÀ¿¿¿¿¿¿¿¿ÇÇÇÇÇÇÖÖÖ¶¶¶¶¶¶ººÀÀÀÀÀÀÀÀÀ¿¿¿¿¿ÇÇÇÇÇÇÇÇÇÇ¶¶¶¶¶¶¶¶ÀÀÀÀÀÀÀÀÀÀÀÀÇÇÇÇÇÇÇÇÇÇÇÇ········ÀÀÀÀÀÀÀÀÀÀÀÀÇÇÇÇÇÇÇÇÇÇÇÇ········ÀÀÀÀÀÀÀÀÀÀÀÀÇÇÇÇÇÇÇÇÇ\022\022\022-----···ÀÀÀÀÀÀÀÀÀÀÀÁÁÇÇÇÇ\022\022\022\022\022\022\022--------·ÀÀÀÀÀÀÁÁÁÁÁÁÁÁÁ\022\022\022\022\022\022\022\022\001\001\001\001\001\030\030\030\030\030\030\030\027\027\027\027\027\027\027ÞÞÞÞÞÞÞ\025\025\025\025\025\001\002\002\002\002\002u\030\030\030\030\030\030\027\027\027\027\027\027ßÞÞÞÞÞÞÞ\025\025\025\025\025\002\002\002\002\002uuu\030\030\030\030\030\030\027\027ßßßßÞÞÞÞÞÝÝÝÝÝÝ\025W\002\002\002uuuuàà\030\030\030\030\030ßßßßßÞÞÞÞÝÝÝÝÝÝÝÝWWWWuuuuààààÊÊÊßßßßßÞÞÞÝÝÝÝÝÝÝÝÝWWWWgggààÊÊÊÊÊËËËËËËËÝÝÝÝÝÝÝÝÝÝÝ²ggg½½½½ÊÊÊÊÊËËËËËËËËËÝÝÝÝÝÝÝÝÝÝ³³gg½½½½½ÊÊÊÊËËËËËËËÌÌÌÌÌÌÎÎÎÎÎÎ³³³³½½½½½½hhËËËËËËÌÌÌÌÌÌÌÎÎÎÎÎÎÎ³³³³³XXXXhhÉÉÉÉËÌÌÌÌÌÌÌÌÌÎÎÎÎÎÎÎ´´´´´XXXXÉÉÉÉÉÉÉÌÌÌÌÌÌÌÌÌÎÎÎÎÎÎÎ´´´´´´XffÉÉÉÉÉÉÉÌÌÌÌÌÏÏÏÏÏÏÎÎÎÎÎ´´´´´ffffffÉÉÉÉÉÉÉÏÏÏÏÏÏÏÏÏÏÎÎÎÎ´´´´´ffffffffÉÉÉ\020\020\020ÏÏÏÏÏÏÏÏÐÐÐÐ×+++++ffffffff\020\020\020\020\020\020ÏÏÏÏÏÏÏÏÐÐ×××+++++fff¼¼¼¼\020\020\020\020\020\020\020\020ÏÏÏÏÐÐÐÐÐ×××+++++¼¼¼¼¼¼¼¼\020\020\020\020\020\020\020\020\020ÐÐÐÐÐÐ××××µµµµµµ¼¼¼¼¼¼¼¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈ×××µµµµµµ¼¼¼¼¼¼¼¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµµ¼¼¼¼¼¼¾¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµµ»»»»»»»¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµ»»»»»»»»»»»¾¾¾¾¾¿¿ÈÈÈÈÈÈÈÈÈÖºººººº»»»»»»»»¿¿¿¿¿¿¿¿¿¿ÈÈÖÖÖÖÖÖºººººº»»»»»»»»¿¿¿¿¿¿¿¿¿¿¿ÖÖÖÖÖÖÖººººººº»»»»»»»¿¿¿¿¿¿¿¿¿¿ÇÇÖÖÖÖÖÖ¶¶¶¶¶ºººÀÀÀÀÀÀ¿¿¿¿¿¿¿¿¿ÇÇÇÇÇÇÖÖÖ¶¶¶¶¶¶¶ºÀÀÀÀÀÀÀÀÀ¿¿¿¿¿ÇÇÇÇÇÇÇÇÇÇ¶¶¶¶¶¶¶¶ÀÀÀÀÀÀÀÀÀÀÀÁÁÇÇÇÇÇÇÇÇÇÇÇ········ÀÀÀÀÀÀÀÀÀÀÀÁÁÇÇÇÇÇÇÇÇÇÇÇ········ÀÀÀÀÀÀÀÀÀÀÁÁÁÁÇÇÇÇÇÇÇ\022\022\022----····ÀÀÀÀÀÀÀÀÁÁÁÁÁÁÁÁÁÇ\022\022\022\022\022\022--------··ÀÀÀÁÁÁÁÁÁÁÁÁÁÁÁ\022\022\022\022\022\022\022\001\030\030\030\030\030\030\030\027ßßßßßßßßÞÞÞÞ\025\025\025\025\025\002\002\002\002\002\030\030\030\030\030\030\030ßßßßßßßßßÞÞÞÝÝÝ\025\025\025\002\002\002\002\002uuu\030\030\030\030\030\030ßßßßßßßßßÝÝÝÝÝÝÝÝÝWW\002\002uuuvv\030\030\030\030\030ßßßßßßßßßÝÝÝÝÝÝÝÝÝWWWWuuvvvvàÊÊÊÊßßßßßßßÝÝÝÝÝÝÝÝÝÝWWWggggvÊÊÊÊÊÊËËËËËËËÝÝÝÝÝÝÝÝÝÝÝ²²gggg½½ÊÊÊÊÊËËËËËËËËËÝÝÝÝÝÝÝÝÝÝ²²³g½½½½½hhÊÊËËËËËËËËÌÌÌÌÌÎÎÎÎÎÎ³³³³½½½½hhhhËËËËËËËËÌÌÌÌÌÎÎÎÎÎÎÎ³³³³XXXXXhhhhÉËËËÌÌÌÌÌÌÌÌÎÎÎÎÎÎÎ´´´´XXXXXXÉÉÉÉÉÉÌÌÌÌÌÌÌÌÏÎÎÎÎÎÎÎ´´´´´XXXXXÉÉÉÉÉÉÍÍÍÏÏÏÏÏÏÏÏÎÎÎÎÎ´´´´´fffffffÉÉÉÉÍÍÍÏÏÏÏÏÏÏÏÏÐÎÎÎ´´´´´ffffffffÉÉÉÍÍÍÏÏÏÏÏÏÏÐÐÐÐÐÐ++++ffffffffff\020\020\020\020\020ÏÏÏÏÏÏÐÐÐÐÐÐÐ+++++ff¼¼¼¼¼¼\020\020\020\020\020\020ÏÏÏÏÏÐÐÐÐÐÐÐ×µµµµµ¼¼¼¼¼¼¼¼¼\020\020\020\020\020\020\020ÐÐÐÐÐÐÐÐÐÐ×µµµµµµ¼¼¼¼¼¼¼¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈ×µµµµµµ¼¼¼¼¼¼¼¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµµ¼¼¼¼¼¼¾¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµµ»»»»»»¾¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµ»»»»»»»»»»¾¾¾¾¾¿¿ÈÈÈÈÈÈÈÈÈÈºººººº»»»»»»»»¿¿¿¿¿¿¿¿¿¿ÈÈÖÖÖÖÖÖºººººº»»»»»»»»¿¿¿¿¿¿¿¿¿¿¿ÖÖÖÖÖÖÖ¶¶¶¶¶ºº»»»»»»¿¿¿¿¿¿¿¿¿¿¿ÇÇÖÖÖÖÖÖ¶¶¶¶¶¶ººÀÀÀÀÀÀ¿¿¿¿¿¿¿¿ÇÇÇÇÇÇÇÇÖÖ¶¶¶¶¶¶¶¶ÀÀÀÀÀÀÀÀ¿¿¿¿¿¿ÇÇÇÇÇÇÇÇÇÇ········ÀÀÀÀÀÀÀÀÀÁÁÁÁÁÁÇÇÇÇÇÇÇÇÇ········ÀÀÀÀÀÀÀÀÀÁÁÁÁÁÁÇÇÇÇÇÇÇÇÇ········ÀÀÀÀÀÀÀÀÁÁÁÁÁÁÁÁÇÇÇÇÇÇ\022\022·········ÀÀÀÀÀÀÁÁÁÁÁÁÁÁÁÁÁ\022\022\022\022\022\022-------···ÀÀÀÁÁÁÁÁÁÁÁÁÁÁÁÁ\022\022\022\022\022\022ôôôôô\030\030ßßßßßßßßßßßÞÞ\025\025\025\025\025\002ôôôô\030\030\030ßßßßßßßßßßÝÝÝÝÝÝÝÝ\002\002ôôôô\030\030\030ßßßßßßßßßÝÝÝÝÝÝÝÝÝWWWWWvvvvvv\030\030\030ßßßßßßßßßÝÝÝÝÝÝÝÝÝWWWW\003\003vvvvÊÊÊÊÊßßßßßßßÝÝÝÝÝÝÝÝÝÝ²²Wgg\003\003vvÊÊÊÊÊËËËËËËËÝÝÝÝÝÝÝÝÝÝÝ²²²gggg½ÊÊÊÊÊÊËËËËËËË\026\026ÝÝÝÝÝÜÜÜÜ²²²²gg½hhhhhÊËËËËËËËË\026\026\026\026\026ÜÜÜÜÜÜ³³³³³½hhhhhhhËËËËËËËË\026\026\026\026\026ÜÜÜÜÜÜ³³³³XXXXXhhhhhËËËËËË\026\026\026\026\026\026ÜÜÜÜÜÜ³³³³XXXXXXXhhtttÍÍÍÍÍÍÍÍÏÏÏÏÎÎÎÎ´´´´XXXXXXXfftÍÍÍÍÍÍÍÍÏÏÏÏÏÏÏÎÎÎ´´´´´fffffffftÍÍÍÍÍÍÏÏÏÏÏÏÏÏÐÐÐÐ´´´´´ffffffffiÍÍÍÍÍÍÏÏÏÏÏÏÐÐÐÐÐÐ±±±±±ffffffffiiÍÍÍÍÍÏÏÏÏÏÐÐÐÐÐÐÐ±±±±±f¼¼¼¼¼¼¼¼iiiiiÏÏÏÏÐÐÐÐÐÐÐÐÐµµµµµ¼¼¼¼¼¼¼¼¼¼¾¾¾¾¾¾ÐÐÐÐÐÐÐÐÐÐÐµµµµµ¼¼¼¼¼¼¼¼¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµµ¼¼¼¼¼¼¼¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµµ¼¼¼¼¼¼¼¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµµµ»»»»¾¾¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµ»»»»»»»»¾¾¾¾¾¾¾¾\021ÈÈÈÈÈÈÈÈÈÈººº,,,,»»»»»»»¿¿¿¿¿¿¿¿\021\021\021\021\021\021ÈÖÖÖºººº,,,»»»»»»»¿¿¿¿¿¿¿¿\021\021\021\021\021\021ÖÖÖÖ¶¶¶¶¶¶,,,,,»»¿¿¿¿¿¿¿¿¿\021\021\021\021\021\021ÖÖÖÖ¶¶¶¶¶¶¶,,,,,,¿¿¿¿¿¿¿¿¿ÇÇÇÇÇÇÇÇÇÇ········,ÀÀÀÀÀ¿¿¿ÁÁÁÁÁÁÇÇÇÇÇÇÇÇÇ·········ÀÀÀÀÀÀÁÁÁÁÁÁÁÁÁÁÇÇÇÇÇÇÇ·········ÀÀÀÀÀÀÁÁÁÁÁÁÁÁÁÁÇÇÇÇÇÇÇ·········ÀÀÀÀÀÁÁÁÁÁÁÁÁÁÁÁÁÇÇÇÇÇÇ·········ÀÀÀÀÀÁÁÁÁÁÁÁÁÁÁÁÁÁ\022\022\022\022\022·········¹¹¹¹ÂÂÂÂÁÁÁÁÁÁÁÁÁÁ\022\022\022\022\022ôôôôôôóóßßßßßßßßßßßÝÝÝÝÝÝôôôôôôóßßßßßßßßßßÝÝÝÝÝÝÝÝWôôôôôôôßßßßßßßßßßÝÝÝÝÝÝÝÝWWvvvvvôôôßßßßßßßßßßÝÝÝÝÝÝÝÝWW\003\003\003\003\003vvvvÊÊÊÊßßßßßßßßÝÝÝÝÝÝÝÝÝ²²²\003\003\003\003vvvvÊÊÊÊËËËËË\026\026ÝÝÝÝÝÝÝÜÜÜ²²²gggg\004\004\004wwwwwËËËË\026\026\026\026\026\026\026ÜÜÜÜÜÜ²²²²g\004\004hhhhhwwwËËËË\026\026\026\026\026\026\026\026ÜÜÜÜÜ³³³³³\004hhhhhhwwwwËËË\026\026\026\026\026\026\026\026ÜÜÜÜÜ³³³³XXXXXhhhhtttttt\026\026\026\026\026\026\026\026ÜÜÜÜÜ³³³³XXXXXXXtttttttÍÍÍÍÍ\026\026\026\026\026ÜÜÜÜ³³³XXXXXXXXtttttÍÍÍÍÍÍÍÏÏÏÏÏÏÚÚÚ±±±±±YYYYYYttttÍÍÍÍÍÍÍÏÏÏÏÏÏÐÐÐÐ±±±±±±[[[[[[iiiiÍÍÍÍÍÍÏÏÏÐÐÐÐÐÐÐ±±±±±±[[[[[[iiiiiÍÍÍÍÏÏÏÐÐÐÐÐÐÐÐ±±±±±±[[[[[[iiiiiiiÏÏÏÏÐÐÐÐÐÐÐÐÐµµµµµ¼¼¼¼¼¼¼¼¼iiiii¾¾ÐÐÐÐÐÐÐÐÐÐÐµµµµµ¼¼¼¼¼¼¼¼¼¾¾¾¾¾¾¾¾ÐÐÐÐÐÐÐÐÐÐµµµµµ¼¼¼¼¼¼¼¼¼¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµµ¼¼¼¼¼¼¼¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµµµµ¼¼¼¼¾¾¾¾¾¾¾¾¾¾¾ÈÈÈÈÈÈÈÈÈÈµµµµ,,,,,,,,,¾¾¾¾¾¾\021\021\021\021\021\021\021\021ÈÈÈÈÈ,,,,,,,,,,,,,,,¿¿\021\021\021\021\021\021\021\021\021\021\021\021\021ÒÒ¶,,,,,,,,,,,,,,¿¿¿\021\021\021\021\021\021\021\021\021\021\021\021\021Ò¶¶¶,,,,,,,,,,,,¿¿¿\021\021\021\021\021\021\021\021\021\021\021\021\021Ö¶¶¶,,,,,,,,,,,¿¿¿¿\021\021\021\021\021\021\021\021\021\021\021\021ÇÇ·······,,,,,,,ÁÁÁÁÁÁÁÁÁÁÁÁ\021ÇÇÇÇÇ········¹¹¹¹¹¹ÁÁÁÁÁÁÁÁÁÁÁÁÁÇÇÇÇÇ········¹¹¹¹¹¹ÁÁÁÁÁÁÁÁÁÁÁÁÁÇÇÇÇÇ········¹¹¹¹¹¹ÁÁÁÁÁÁÁÁÁÁÁÁÁÇÇÇÇÇ········¹¹¹¹¹¹ÂÂÁÁÁÁÁÁÁÁÁÁÁÁÇÇÇ\022·······¹¹¹¹¹¹¹ÂÂÂÂÂÂÂÂÂÂÂÂÂÂ\022\022\022\022ôôôôóóóóóßßßßßßßßáááááÝÝÝôôôôôóóóßßßßßßßßßáááÝÝÝÝÝôôôôôôóóßßßßßßßßßááÝÝÝÝÝÝôôôôßßßßßßßßßßááÝÝÝÝÝÝ\003\003\003ÊßßßßßßßßßßÝÝÝÝÝÝÝÜ²²HHHHwwwwwßß\026\026\026\026\026\026\026ÜÜÜÜÜÜ²²²HHH\004\004\004\004wwwwwwwË\026\026\026\026\026\026\026\026\026ÜÜÜÜÜ²²²²H\004\004\004\004wwwwwwwxx\026\026\026\026\026\026\026\026\026ÜÜÜÜÜ³³³II\004\004\004hwwwwwwxxxx\026\026\026\026\026\026\026\026ÜÜÜÜÜ³³³IIXXXXhhhttttttt\026\026\026\026\026\026\026\026ÜÜÜÜÜ³³³³XYYYYYYtttttttÍÍÍÍ\026\026\026\026\026\026ÚÚÚÚ±±±XYYYYYYYttttttÍÍÍÍÍÍÍÏÏÏÚÚÚÚÚ±±±±±YYYYYYtttttÍÍÍÍÍÍÍÏÏÏÏÐÚÚÚÚ±±±±±±[[[[[[iiiiÍÍÍÍÍÍÍÐÐÐÐÐÐÐÐÐ±±±±±±[[[[[[iiiiiiÍÍÍÍÍÐÐÐÐÐÐÐÐÐ±±±±±±[[[[[[iiiiiiiiÏØØÐÐÐÐÐÐÐÐÐ±±±±±[[[¼¼¼¼iiiiiiiiØØØÐÐÐÐÐÐÐÐÐµµµµµ¼¼¼¼¼¼¼¼¼¼¾¾¾¾¾ØØØØÐÐÐÐÐÐÐÐµµµµµ¼¼¼¼¼¼¼¼¼¼¾¾¾¾¾¾ØØØØÈÈÒÒÒÒÒµµµµµ¼¼¼¼¼¼¼¼¼¼¾¾¾¾¾¾¾ÈÈÈÈÒÒÒÒÒÒµµµµµµ,,¼¼¼¼¾¾¾¾¾¾¾¾¾\021\021\021ÈÈÒÒÒÒÒÒµµ,,,,,,,,,,,,¾¾¾¾\021\021\021\021\021\021\021\021\021\021ÒÒÒÒ,,,,,,,,,,,,,,,,\021\021\021\021\021\021\021\021\021\021\021\021\021ÒÒÒ,,,,,,,,,,,,,,,,\021\021\021\021\021\021\021\021\021\021\021\021\021\021ÒÒ¶,,,,,,,,,,,,,,,\021\021\021\021\021\021\021\021\021\021\021\021\021\021\021Ô··,,,,,,,,,,,,,,\021\021\021\021\021\021\021\021\021\021\021\021\021\021\021Å·····,,,,,,,,,¹ÁÁÁÁÁÁÁÁÁÁ\021\021\021\021ÅÅÅ·······¹¹¹¹¹¹¹¹ÁÁÁÁÁÁÁÁÁÁÁÁÁÅÅÅÅ······¹¹¹¹¹¹¹¹¹¹ÁÁÁÁÁÁÁÁÁÁÁÁÅÅÅÅ······¹¹¹¹¹¹¹¹¹¹ÁÁÁÁÁÁÁÁÁÁÁÁÅÅÅÅ······¹¹¹¹¹¹¹¹¹ÂÂÂÂÂÂÂÂÁÁÁÁÁÅÅÅÅ·····¹¹¹¹¹¹¹¹¹¹ÂÂÂÂÂÂÂÂÂÂÂÂÂÅÅÅÅôôôôóóóóóóóßßßßááááááááâââôôôôôóóóóóßßßßßááááááááâââôôôôôóóóóßßßßßááááááááââÝôôôßßßßßßßááááááááââÝßßßßßßßáááááááÜÜÜÜ²HHHHHwwww\026\026\026\026\026\026\026\026ÜÜÜÜÜÜ²HHHHH\004\004\004wwwwwww\026\026\026\026\026\026\026\026\026ÜÜÜÜÜ²²III\004\004\004\004wwwwwwxxxx\026\026\026\026\026\026\026\026ÜÜÜÜÜ²IIIIII\004\004wwwwwxxxxx\026\026\026\026\026\026\026\026ÜÜÜÜÜIIIIIIVVV\005\005\005xxxxxxx\026\026\026\026\026\026\026\026ÜÜÜÜÜ³IIVVYYYYYYttttttttÍÍÍ\026\026\026\026\026ÚÚÚÚÚ±±VVVYYYYYYttttttÍÍÍÍÍÍÍÍÚÚÚÚÚÚÚ±±±±±YYYYYYtttttÍÍÍÍÍÍÍÍÐÐÚÚÚÚÚÚ±±±±±±[[[[[[iiiiiÍÍÍÍÍÍØÐÐÐÐÐÚÚÚ±±±±±±[[[[[[iiiiiiÍÍÍÍØØØØØØÐÐÐÐ±±±±±±[[[[[[iiiiiiiiØØØØØØØØÐÐÐÐ±±±±±[[[[[[[iiiiiiiiØØØØØØØØÐÐÐÐµµµUUU¼¼eeeeeeeeeiiØØØØØØØØØØÐÐÒµµµµ¼¼¼¼¼¼¼¼¼\\\\\\eerrrrrrrrrÒÒÒÒÒµµµµ¼¼¼¼¼¼\\\\\\\\\\\\\\¾rrrrrrrrÒÒÒÒÒÒµµµ,,,,,,,,,\\\\\\\\¾¾\021\021\021\021\021\021\021ÒÒÒÒÒÒÒµ,,,,,,,,,,,,,,,\021\021\021\021\021\021\021\021\021\021\021ÒÒÒÒÒ,,,,,,,,,,,,,,,,\021\021\021\021\021\021\021\021\021\021\021\021ÒÒÒÒ,,,,,,,,,,,,,,,,\021\021\021\021\021\021\021\021\021\021\021\021\021\021ÔÔ,,,,,,,,,,,,,,,,\021\021\021\021\021\021\021\021\021\021\021\021\021\021ÔÔ,,,,,,,,,,,,,,,,\021\021\021\021\021\021\021\021\021\021\021\021\021\021ÔÔ··,,,,,,,,,,,¹¹ÁÁÁÁÁÁÁÁÁÁ\021\021\021ÅÅÅÅ·····¹¹¹¹¹¹¹¹¹¹¹ÁÁÁÁÁÁÁÁÁÁÁÅÅÅÅÅ·····¹¹¹¹¹¹¹¹¹¹¹ÁÁÁÁÁÁÁÁÁÁÁÅÅÅÅÅ····¹¹¹¹¹¹¹¹¹¹¹¹ÁÁÁÁÁÁÁÁÁÁÁÅÅÅÅÅ····¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÂÂÂÂÂÂÁÁÅÅÅÅÅ····¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÂÂÂÂÂÂÂÂÂÅÅÅÅ\"ôôôôóóóóóóóóááááááááááâââââôôôôóóóóóóóááááááááááâââââôôôôôóóóóññáááááááááâââââôôñññññáááááááááâââââñññññáááááááááâââÜÜHHHHHHwwñññ\026\026\026\026\026\026ÜÜÜÜÜÜHHHHHH\004wwww\026\026\026\026\026\026\026\026ÜÜÜÜÜHHIIII\004\004wwwwwxxxx\026\026\026\026\026\026\026\026ÜÜÜÜÜ¤IIIIII\005\005\005wwwxxxxxx\026\026\026\026\026\026\026\026ÜÜÜÜÜ¤¤IIIVVV\005\005\005\005xxxxxxxy\026\026\026\026\026\026\026ÜÜÜÜÜ¤¤¤¤VVVVY\005\005\005ttttttyyy\026\026\026\026\026\026ÚÚÚÚÚ¤¤¤¤VVYYYYZZtttttÍÍÍÍÍÍÍÍÚÚÚÚÚÚÚ±±±±±YYYYZZZtttttÍÍÍÍÍÍÍÍÚÚÚÚÚÚÚ±±±±±±[[[[[[iiiiiÍÍÍÍÍÍØØØØØÚÚÚÚ±±±±±±[[[[[[iiiiiisÍÍØØØØØØØØØØÚ±±±±±±[[[[[[iiiiiisssØØØØØØØØØØÐ±±±±UUU[[eeeeeeeiissØØØØØØØØØØØÐ±UUUUUUU\\\\eeeeeeeesØØØØØØØØØØØÒÒ°UUUU\\\\\\\\\\\\\\\\\\\\\\\\\\rrrrrrrrrrÒÒÒÒ°°°°\\\\\\\\\\\\\\\\\\\\\\\\\\\\rrrrrrrrrÒÒÒÒÒ°°,,,,,\\\\\\\\\\\\\\\\\\\\jjjrrrrrrÒÒÒÒÒÒ°,,,,,,,,,,,,,,^^\021\021\021\021\021\021\021\021\021\021ÒÒÒÒÒ°,,,,,,,,,,,,,,^^\021\021\021\021\021\021\021\021\021\021\021ÒÒÒÒ,,,,,,,,,,,,,,,^^\021\021\021\021\021\021\021\021\021\021\021ÔÔÔÔ,,,,,,,,,,,,,,,^^\021\021\021\021\021\021\021\021\021\021\021ÔÔÔÔ,,,,,,,,,,,,,,^^^\021\021\021\021\021\021\021\021\021\021\021ÔÔÔÔ,,,,,,,,,,,,,¹¹ÁÁÁÁÁÁÁÁ\021\021\021\021ÅÅÅÅÅ··,,¹¹¹¹¹¹¹¹¹¹¹¹ÁÁÁÁÁÁÁÁÁÁÅÅÅÅÅÅ···¹¹¹¹¹¹¹¹¹¹¹¹¹ÁÁÁÁÁÁÁÁÁÁÅÅÅÅÅÅ···¹¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÂÂÂÂÂÁÁÅÅÅÅÅÅ··¹¹¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÂÂÂÂÂÂÂÅÅÅÅÅÅ··¹¹¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÂÂÂÂÂÂÂÂÅÅÅÅÅ\"\"\"\"\"\"ôôóóóóóóóóóáááááááááââââââ\"\"\"\"\"\"õõõóóóóóóóññááááááááââââââ\"\"\"õõõõõõóññññññááááááááââââââ\"õõõõõññññññááááááááââââââñññññññáááááááââââââHHHHññññññ\026\026\026\026\026\026ÜÜÜÜÜÜ£HHHH\026\026\026\026\026\026\026\026ÜÜÜÜÜ£IIIIII\026\026\026\026\026\026\026\026ÜÜÜÜÜ¤¤IIIII\005\005\005\005\005xxxxxxx\026\026\026\026\026\026\026\026ÜÜÜÜÜ¤¤¤¤IVVV\005\005\005\005xxxxxxyy\026\026\026\026\026\026\026ÜÜÜÜÜ¤¤¤¤VVVV\005\005\005\005xxxxyyyyyy\026\026\026\026\026ÚÚÚÚÚ¤¤¤¤VVVVZZZZZtttyyyyyyyÍÍÚÚÚÚÚÚÚ±±±±±±YZZZZZZtttyyyyyyyÍØÚÚÚÚÚÚÚ±±±±±±[ZZZZZZiiissssssØØØØØØÚÚÚÚ±±±±±±UU[[eeeeiisssssØØØØØØØØØØÚ±±±±±UUUUUeeeeeisssssØØØØØØØØØØØ±±±UUUUUUUeeeeeesssssØØØØØØØØØØØ±UUUUUUU\\\\\\\\\\\\eeeesrrrrrrrrrrrÒÒ°°°UUU\\\\\\\\\\\\\\\\\\\\\\\\rrrrrrrrrrÒÒÒÒ°°°°°\\\\\\\\\\\\\\\\\\\\\\\\jjjrrrrrrrrÒÒÒÒ°°°°°,\\\\\\\\\\\\\\\\\\\\jjjjjjjjjrÒÒÒÒÒÒ°°°,,,,,,,,,,^^^^jjj\021\021\021\021\021\021\021ÒÒÒÒÒ°°,,,,,,,,,,,^^^^^^\021\021\021\021\021\021\021\021\021ÒÒÒÒ°,,,,,,,,,,,,^^^^^^\021\021\021\021\021\021\021\021\021ÔÔÔÔ,,,,,,,,,,,,,^^^^^^\021\021\021\021\021\021\021\021\021ÔÔÔÔ,,,,,,,,,,,,,^^^^^^\021\021\021\021\021\021\021\021ÔÔÔÔÔ¯,,,,,,,,,,,,^^^^^^\021\021\021\021\021\021\021ÅÅÅÅÅÅ¯¯¯,¹¹¹¹¹¹¹¹¹¹¹¹¹ÁÁÁÁÁÁÁÁÅÅÅÅÅÅÅ¯¯¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹ÁÁÁÁÁÁÁÁÅÅÅÅÅÅÅ·¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÂÂÂÂÂÂÂÅÅÅÅÅÅ·¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÂÂÂÂÂÂÂÅÅÅÅÅÅ¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÂÂÂÂÂÂÂÃÃÃÅÅÅ\"\"\"\"\"\"\"óóóóóóóóóóáááááááââââââââ\"\"\"\"\"\"õõõóóóóóóñññááááááââââââââ\"\"\"\"\"õõõõõòòñññññññáááááââââââââ\"\"\"\"\"õõõõõõòñññññññáááááââââââââ\"\"\"òñññññññáááááââââââââ£££££ññññññññ\026\026\026\026\026ÜÜÜÜÜÜ£££££\026\026\026\026\026\026\026\026ÛÛÛÛÛ£££££=====\026\026\026\026\026\026\026\026ÛÛÛÛÛ¤¤¤II====\005\005\005\026\026\026\026\026\026\026\026ÛÛÛÛÛ¤¤¤¤VVVV\005\005\005\005xxxxxyyyy\026\026\026\026\026\026ÛÛÛÛÛ¤¤¤¤VVVV\005\005\005\006\006xxxyyyyyyy\026\026\026ÛÚÚÚÚÚ¤¤¤¤VVVVZZ\006\006\006\006\006yyyyyyyyyyÚÚÚÚÚÚÚ¥¥¥¥±±ZZZZZZ\006\006\006yyyyyyyyyØÚÚÚÚÚÚÚ¥¥¥¥±±ZZZZZZZZssssssssØØØØØØÚÚÚÚ¥±±±UUUUUUeeeeessssssØØØØØØØØØØÚ±±±±UUUUUUeeeeessssssØØØØØØØØØØØ±±UUUUUUUUeeeeeesssssØØØØØØØØØØØ°UUUUUUU\\\\\\\\\\\\\\ee33rrrrrrrrrrrÒÒ°°°°°U\\\\\\\\\\\\\\\\\\\\\\33rrrrrrrrrrÒÒÒ°°°°°°\\\\\\\\\\\\\\\\\\\\jjjjjrrrrrrrÒÒÒÒ°°°°°°°\\\\\\\\\\\\\\\\\\jjjjjjjjjÑÑÒÒÒÒÒ°°°°°,,,,,,,^^^^^jjjjjjjj\021\021ÒÒÒÒÒ°°°°,,,,,,,,^^^^^^^jjjj\021\021\021\021\021ÔÔÔÔ°°°,,,,,,,,,^^^^^^^^\021\021\021\021\021\021\021ÔÔÔÔÔ¯¯,,,,,,,,,,^^^^^^^^\021\021\021\021\021\021\021ÔÔÔÔÔ¯¯¯,,,,,,,,,^^^^^^^^\021\021\021\021\021\021\021ÔÔÔÔÔ¯¯¯¯,,,,,,,,^^^^^^^^\021\021\021\021\021\021ÅÅÅÅÅÅ¯¯¯¯¯¹¹¹¹¹¹¹¹¹¹¹¹¹ÁÁÁÁÁÁÅÅÅÅÅÅÅÅ¯¯¯¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÂÂÂÃÅÅÅÅÅÅÅ¯¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÂÂÂÂÃÃÃÅÅÅÅÅ¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÂÂÂÂÂÃÃÃÃÅÅÅÅ¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÂÂÂÂÂÃÃÃÃÃÃÃÅ\"\"\"\"\"\"\"\"óóóóóóóóòáááááááââââââââ\"\"\"\"\"\"õõõòòòòòòòññááááááââââââââ\"\"\"\"õõõõõòòòòñññññáááááââââââââ\"\"\"õõõõõòòòòñññññáááááââââââââ\"õõõõõõòòòññññññáááááââââââââ£££££ññññññññáááâââÛÛÛÛÛ££££££=====\026\026\026\026\026\026ÛÛÛÛÛÛ£££££======\026\026\026\026\026ÛÛÛÛÛÛÛ¤¤¤¤=======yy\026\026\026\026ÛÛÛÛÛÛÛ¤¤¤¤¤==\005\005\005\005\005xxxxyyyyyyy\026\026ÛÛÛÛÛÛÛ¤¤¤¤¤VV\005\005\006\006\006\006\006xyyyyyyyyy\026ÛÛÛÚÚÚÚ¥¥¤¤¤VVZZ\006\006\006\006\006\006yyyyyyyyyzÚÚÚÚÚÚÚ¥¥¥¥¥ZZZZZZ\006\006\006\006yyyyyyyzzzzÚÚÚÚÚÚ¥¥¥¥¥UZZZZZZZZssssssssØØØØØØÚÚÚÚ¥¥¥¥UUUUUUeeeeesssssssØØØØØØØØÙÙ±±UUUUUUUUeeeeessssssØØØØØØØØØÙÙ±UUUUUUUUUeeeeee3333srrrrrrrrØØÙ°UUUUUUU\\\\\\\\\\\\\\33333rrrrrrrrrrrÒ°°°°°U\\\\\\\\\\\\\\\\\\\\3333ÑrrrrrrrrÒÒÒ°°°°°°\\\\\\\\\\\\\\\\\\\\]jjjjÑÑÑÑÑrrÒÒÒÒ°°°°°°°\\\\\\\\]]]]]]jjjjjjjÑÑÑÑÒÒÒÒ°°°°°°°,,,]]]]^^^jjjjjjjjÑÑÒÒÒÒÒ°°°°°°,,,,,]^^^^^^^jjjjjjj\021ÔÔÔÔÔ¯¯¯¯¯,,,,,,^^^^^^^^^jjjjj\021ÔÔÔÔÔÔ¯¯¯¯¯,,,,,,^^^^^^^^^^\021\021\021\021\021ÔÔÔÔÔÔ¯¯¯¯¯,,,,,,^^^^^^^^^^\021\021\021\021\021ÔÔÔÔÔÔ¯¯¯¯¯¯¯,,,¹^^^^^^^^^^^\021\021\021ÅÅÅÅÅÅÅ¯¯¯¯¯¹¹¹¹¹¹¹¹¹¹¹¹¹¹^^^\021ÆÅÅÅÅÅÅÅÅ¯¯¯¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÃÃÃÃÃÃÅÅÅÅÅÅ¯¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÃÃÃÃÃÃÃÃÅÅÅÅ¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÃÃÃÃÃÃÃÃÃÅÅÅ¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹ÂÂÂÃÃÃÃÃÃÃÃÃÃÃÅ\"\"\"\"\"\"\"\"òòòòòòòò\031\031ááááââââââââåå\"\"\"\"\"\"õõõòòòòòòòñññáááâââââââââå\"\"õõõõõòòòòòññññáááâââââââââå\"\"õõõõõòòòòñññññáááââââââââââõõõõõõòòòñññññññááââââââââââ£££££££õõõõñññññññññáâââââÛÛÛÛÛ££££££=====\026\026ãÛÛÛÛÛÛÛÛ£££££======\026\026\026ÛÛÛÛÛÛÛÛ¤¤££=======yyyyyÛÛÛÛÛÛÛÛ¤¤¤¤¤>>>>>>yyyyyyyyÛÛÛÛÛÛÛÛ¤¤¤¤¤GGGG\006\006\006\006\006\006yyyyyyyyyÛÛÛÛÛÛÛÛ¥¥¥¥¤GGGG\006\006\006\006\006\006yyyyyyyyzzzÙÚÚÚÚÚ¥¥¥¥GGGG\006\006\006\006\006\006\007\007yyyyzzzzzzÙÙÙÙÙÙ¥¥¥¥JJJJJJZ\007\007\007\007\007sssszzzz{{{{ÙÙÙÙ¥¥¥¥JUUUUUU\007\007\007\007\007sssszzzz{{{{ÙÙÙÙ***JUUUUUUUe\007\0073333ssszz{{{{{ÙÙÙÙ***UUUUUUU\\\\\\\\3333333rrrrrrrrr\024\024***UUUUU\\\\\\\\\\\\3333333rrrrrrrrr\024\024°°°°°°\\\\\\\\\\\\\\\\\\33333ÑÑÑÑÑÑrrrrÒÒ°°°°°°\\\\\\\\\\\\\\\\]]]]jjjÑÑÑÑÑÑÑrÒÒÒ°°°°°°°\\\\]]]]]]]]]jjjjjÑÑÑÑÑÑÒÒÒ°°°°°°°°]]]]]]]]]]jjjjjjjÑÑÑÒÒÒÒ°°°°°°°°]]]]]]^^^^^jjjjjjjjÔÔÔÔÔ¯¯¯¯¯¯¯¯]]]]^^^^^^^^jjjjjjÔÔÔÔÔÔ¯¯¯¯¯¯¯¯,,^^^^^^^^^^^^^jÆÆÔÔÔÔÔÔ¯¯¯¯¯¯¯¯,^^^^^^^^^^^^^^ÆÆÆÔÔÔÔÔÔ¯¯¯¯¯¯¯¯¹¹¹^^^^^^^^^^^^ÆÆÆÆÅÅÅÅÅ¯¯¯¯¯¯¹¹¹¹¹¹¹¹¹¹¹¹^^^^ÆÆÆÆÆÅÅÅÅÅ¯¯¯¯¹¹¹¹¹¹¹¹¹¹¹¹¹¹¸ÃÃÃÃÃÃÃÅÅÅÅÅÅ®¹¹¹¹¹¹¹¹¹¹¹¹¹¹¹¸¸¸ÃÃÃÃÃÃÃÃÃÅÅÅÅ®¹¹¹¹¹¹¹¹¹¹¹¹¹¹¸¸¸¸ÃÃÃÃÃÃÃÃÃÃÅÅÅ¹¹¹¹¹¹¹¹¹¹¹¹¹¸¸¸¸¸¸ÃÃÃÃÃÃÃÃÃÃÃÃÃ!!!!!!\"\"òòòò\031\031\031\031\031\031\031\031\031âââââââåååå\"\"õõòòòòòòòò\031\031\031\031áââââââââååå\"õõõõòòòòòòòññññáâââââââââåå\"õõõõòòòòòòòññññáâââââââââââ£õõõõòòòòòòññññññâââââââââââ£££££££õõõ=òñññññññññãããããããããÛÛ££££££>>>>>ãããããÛÛÛÛÛÛ£££££>>>>>>ããããÛÛÛÛÛÛÛ###£>>>>>>>ãããÛÛÛÛÛÛÛ####>>>>>>>ÛÛÛÛÛÛÛÛ¥¥¥¥GGGGGGG\006\006\006ÛÛÛÛÛÛÛÛ¥¥¥¥GGGGGG\006\006\006\006zzzÙÙÙÙÙÙÙ¥¥¥¥GGGGGG\006\006\007\007\007\007zzzzzz{ÙÙÙÙÙÙ¥¥¥¥JJJJJJJ\007\007\007\007\007\007szzzzzz{{{ÙÙÙÙÙ****JJJJJJJ\007\007\007\007\007\0073zzzzz{{{{{ÙÙÙÙ****JJJJJJJ\007\007\007333333zz{{{{{{ÙÙÙÙ****UUUUTTTTTT3333333{{{{{{{{\024\024\024****UUUTTTTTTT33333322rrrrrr\024\024\024\024°°°°°°TTTTTTTTT222222ÑÑÑÑÑÑÑÑ\024\024\024°°°°°°TTTTTTTT]]]222jÑÑÑÑÑÑÑÑÑÒÒ°°°°°°°TT]]]]]]]]]jjjjjÑÑÑÑÑÑÑÒÒ°°°°°°°°]]]]]]]]]]jjjjjjjÑÑÑÑÑÔÔ°°°°°°°°]]]]]]]]^^^jjjjjjjjÔÔÔÔÔ¯¯¯¯¯¯¯¯]]]]^^^^^^^^jjjjjjÔÔÔÔÔÔ¯¯¯¯¯¯¯¯SS^^^^^^^^^^^^^jÆÆÆÔÔÔÔÔ¯¯¯¯¯¯¯¯SS^^^^^^^^^^^^^ÆÆÆÆÆÆÆÔÔ¯¯¯¯¯¯¯¯¯^^^^^^^^^^^^^kÆÆÆÆÆÆÆÆÆ¯¯¯¯¯¯¯¹¹¹¹¹¹¹¹¹¹^^__kÆÆÆÆÆÆÆÆÆÆ¯¯¯¯¯¹¹¹¹¹¹¹¹¸¸¸¸¸¸¸ÃÃÃÃÃÆÆÆÆÅÅÅ®®®®¹¹¹¹¹¸¸¸¸¸¸¸¸¸¸¸ÃÃÃÃÃÃÃÃÃÅÅÅ®®®¹¹¹¹¹¹¸¸¸¸¸¸¸¸¸¸¸ÃÃÃÃÃÃÃÃÃÃÅÅ®¹¹¹¹¹¹¹¸¸¸¸¸¸¸¸¸¸¸¸ÃÃÃÃÃÃÃÃÃÃÃÃ!!!!!!!òòòò\031\031\031\031\031\031\031\031\031\031ââââââååååå!!!!!!ÿòòòòòò\031\031\031\031\031\031\031\031ðââââââååååÿòòòòòòòòò\031\031\031\031ððâââââââåååõòòòòòòòòòòñññððâââââââââå££õòòòòòòòòòñññðððââââââããã££££££££òòòòòòòñññññãããããããããããã###££>>>>>>ãããããããããÛÛÛ####>>>>>>>ããããããããÛÛÛÛ####>>>>>>>ããããÛÛÛÛÛÛ####>>>>>>>ÛÛÛÛÛÛÛ¥¥¥GGGGGGGGGÛÛÛÛÛÛ¥¥¥¥GGGGGGGG\006zzzÙÙÙÙÙÙ¥¥¥¥GGGGGG44\007\007\007\007zzzzzzÙÙÙÙÙÙÙ¥¥¥¥JJJJJJ44\007\007\007\007\007zzzzzzz{{{ÙÙÙÙÙ****JJJJJJ44\007\007\007\007\007\bzzzzz{{{{ÙÙÙÙÙ****JJJJJJJ4\007\007\b33333zz{{{{{{Ù\024\024\024*****JJJTTTTTT\b333333{{{{{{{\024\024\024\024******TTTTTTTT33332222ÑÑÑÑÑ\024\024\024\024\024°°°°°°TTTTTTTTT222222ÑÑÑÑÑÑÑÑ\024\024\024°°°°°°TTTTTTTT]]22222ÑÑÑÑÑÑÑÑÑÓÓ°°°°°°°TT]]]]]]]]]jjjjÑÑÑÑÑÑÑÑÓÓ°°°°°°°SS]]]]]]]]]jjjjjjÑÑÑÑÑÓÓÓ°°°°°°°SSS]]]]]]]^^jjjjjjjqqÔÔÔÔ¯¯¯¯¯¯¯SSSS]^^^^^^^^djjjjkkÔÔÔÔÔ¯¯¯¯¯¯¯SSSSS^^^^^^^^^dkkkÆÆÆÆÆÆÆ¯¯¯¯¯¯¯SSSS^^^^^^^^^^kkkkÆÆÆÆÆÆÆ¯¯¯¯¯¯¯¯SSS^^^^^^^^__kkkÆÆÆÆÆÆÆÆ¯¯¯¯¯¯¯®®®¹¹_________kkÆÆÆÆÆÆÆÆÆ®®®®®®®®¸¸¸¸¸¸¸¸¸¸¸¸ÃÃÃÃÆÆÆÆÆÆÆÆ®®®®®®®¸¸¸¸¸¸¸¸¸¸¸¸¸ÃÃÃÃÃÃÃÃÃÃÄÄ®®®®­­­¸¸¸¸¸¸¸¸¸¸¸¸¸ÃÃÃÃÃÃÃÃÃÃÃÄ®­­­­­­¸¸¸¸¸¸¸¸¸¸¸¸¸ÃÃÃÃÃÃÃÃÃÃÃÃ!!!!!!ÿÿÿÿò\031\031\031\031\031\031\031\031\031\031ðððððåååååå!!!!!ÿÿÿÿÿòò\031\031\031\031\031\031\031\031ðððððððååååå!!!!ÿÿÿÿÿÿòòò\031\031\031\031\031\031\031ððððððððååååÿÿÿÿÿÿÿòòòòòòòòððððððððððââååÿÿÿÿÿÿòòòòòòòòððððððððãããããã££££ÿÿþþþþððããããããããããã####>>>>>>>ãããããããããããÛ####>>>>>>>ããããããããããÛÛ####>>>>>>>ãããããããããÛÛ####>>>>>>>ÛÛÛÛ###GGGGGGGG???ÛÛÛ¥¥¥GGGGGGGG???ÙÙÙ¥¥¥GGGGGGG4444\007\007zzzzzzÙÙÙÙÙÙ¥¥¥JJJJJJJ4444\007\007\007zzzzzzz{{{ÙÙÙÙÙ****JJJJJJ4444\007\007\b\bzzzzz{{{{ÙÙÙÙÙ****JJJJJJ444\b\b\b\b\b3zzz{{{{{{Ù\024\024\024*****JJJTTTTT\b\b\b\b\b332{{{{{{{\024\024\024\024******TTTTTTTT\b\b222222||||||\024\024\024\024**°°°TTTTTTTTTT2222222ÑÑÑÑÑ||\024\024\024°°°°°°TTTTTTTT]2222222ÑÑÑÑÑÑÑÓÓÓ°°°°°°°TT]]]]]]]]]dddÑÑÑÑÑÑqÓÓÓÓ°°°°°°SSSS]]]]]]]dddddqqqqqqÓÓÓÓ°°°°°°SSSSS]]]]]ddddddqqqqqqÓÓÓÓ¯¯¯¯¯¯SSSSSSSSddddddddkkkkkkÓÓÓÓ¯¯¯¯¯¯SSSSSSSS^______kkkkkkkÆÆÆÆ¯¯¯¯¯¯SSSSSSSS_______kkkkkÆÆÆÆÆÆ¯¯¯¯¯¯®®SSSS_________kkkkÆÆÆÆÆÆÆ¯®®®®®®®®­­­­________kkkÆÆÆÆÆÆÆÆ®®®®®®®®­­­¸¸¸¸¸¸¸¸¸ÃÃÃÃÆÆÆÆÆÆÆÆ®®®®®®®­­¸¸¸¸¸¸¸¸¸¸¸ÃÃÃÃÃÃÃÃÄÄÄÄ®®®­­­­­­¸¸¸¸¸¸¸¸¸¸¸ÃÃÃÃÃÃÃÃÃÄÄÄ­­­­­­­­¸¸¸¸¸¸¸¸¸¸¸¸ÃÃÃÃÃÃÃÃÃÃÄÄ!!!!!ÿÿÿÿÿÿ\031\031\031\031\031\031\031\031\031\031ððððððååååå!!!!ÿÿÿÿÿÿÿ\031\031\031\031\031\031\031\031\031ðððððððååååå!!!ÿÿÿÿÿÿÿÿÿ\031\031\031\031\031\031\031ðððððððððåååå!!ÿÿÿÿÿÿÿÿÿÿÿöööööðððððððððððåååÿÿÿÿÿÿÿÿÿÿÿþööööööððððððððããããã####ÿÿþþþþþþþþþööööððããããããããããã#####>>þþþþþþãããããããããããä####>>>>>>þþããããããããããää####>>>>>>>ãããããããããää####>>>>??????äää###GGGGG??????ää¢¢¢GGGGG??????ÙÙÙ¥¥GGGGGGG444444zzÙÙÙÙÙ***JJJJJFF444444\007zzzzzz{{{{ÙÙÙÙÙ****JJJJFFF4444\b\b\b\bzzzz{{{{ÙÙÙÙÙ****JJJJF¦¦¦¦¦\b\b\b\b\b\bzz{{{{{{\024\024\024\024*****JJK¦¦¦¦¦¦\b\b\b\b\b\t\t|||||||\024\024\024\024******KKKKKKKK\b\b222222||||||\024\024\024\024*****KKKKKKKKKK2222222|||||||\024\024\024°°°°°°KKKKKKKK]2222222|||||||ÓÓÓ°°°°°°KKKK]]]]]]]ddddqqqqqqqÓÓÓÓ°°°°°°SSSSS]]]]ddddddqqqqqqqÓÓÓÓ¯¯¯¯¯SSSSSSSSS]ddddddqqqqqqqÓÓÓÓ¯¯¯¯¯¯SSSSSSSSdddddddkkkkkkkÓÓÓÓ¯¯¯¯¯¯SSSSSSSS_______kkkkkkkÆÆÆÆ¯¯¯¯®®®SSSSSSS_______kkkkkkÆÆÆÆÆ¯®®®®®®®®SSS_________kkkkkÆÆÆÆÆÆ®®®®®®®®­­­­­­______``kkÆÆÆÆÆÆÆÆ®®®®®®®­­­­­­­¸¸¸```````ÆÆÆÆÄÄÄÄ®®®®®­­­­­­¸¸¸¸¸¸¸¸¸ÃÃÃÃÃÃÄÄÄÄÄÄ®®®­­­­­­­¸¸¸¸¸¸¸¸¸¸ÃÃÃÃÃÃÃÄÄÄÄÄ­­­­­­­­­­¸¸¸¸¸¸¸¸¸¸ÃÃÃÃÃÃÃÃÄÄÄÄ!!!!!ÿÿÿÿÿÿ\031\031\031\031\031\031\031\031\031\031ððððððååååå!!!!ÿÿÿÿÿÿÿÿ\031\031\031\031\031\031\031\031ððððððððåååå!!!ÿÿÿÿÿÿÿÿÿ\031\031öööööðððððððððååååÿÿÿÿÿÿÿÿÿööööööööðððððððððåååÿÿÿÿÿÿÿÿþööööööööðððððððããããã###ÿÿþþþþþþþþþööööööðããããããããããã#####þþþþþþþþþþöããããããããããää#####þþþþþþþþþþãããããããããäää#####>þþþþþþþþãããããããääää####¢>????????ääää¢¢¢¢¢G????????äää¢¢¢¢¢G????????ÙÙ¢¢¢GGGG???44444ÙÙÙÙ****JJJFFFFFF4444zzz{{{{{ÙÙÙÙÙ*****JJFFFFFFF4\b\b\b\b\bzz{{{{{ÙÙ\024\024\024*****JJFFF¦¦¦¦\b\b\b\b\b\b\b{{{{{{Ù\024\024\024\024******KK¦¦¦¦¦¦\b\b\b\b\b\t\t|||||||\024\024\024\024******KKKKKKKK\b\b\t\t\t\t\t|||||||\024\024\024\024*****KKKKKKKKKK\t\t\t\t\t\t||||||||\024\024\024§§§§§KKKKKKKKKK\t\t\t\t\t\t2|||||||ÓÓÓ§§§°°LLLLLLLLLdddddddqqqqqqqÓÓÓÓ°°°°°SSSSSSSSSdddddddqqqqqqqÓÓÓÓ¯¯¯¯¯SSSSSSSSSdddddddqqqqqqqÓÓÓÓ¯¯¯¯¯SSSSSSSSSdddddd_kkkkkkkÓÓÓÓ¯¯¯¯¯®SSSSSSSS_______kkkkkkkkÆÆÆ®®®®®®®SSSSSSS_______kkkkkkkÆÆÆÆ®®®®®®®®®SSS_________`kkkkkÆÆÆÆÆ®®®®®®®®­­­­­­_``````````ÆÆÆÆÆÆÄ®®®®®®­­­­­­­­­``````````ÆÆÆÄÄÄÄ®®®®®­­­­­­­­¸¸¸¸¸¸¸``ccccÄÄÄÄÄÄ®®®­­­­­­­­­¸¸¸¸¸¸¸¸¸.cÃÃÄÄÄÄÄÄÄ­­­­­­­­­­­¸¸¸¸¸¸¸¸¸¸.ÃÃÃÃÄÄÄÄÄÄ!!!!!ÿÿÿÿÿÿ\031\031\031\031\031\031\031\031\031ðððððððåååååÿÿÿÿÿÿÿÿ\031\031\031\031\031\031\031ðððððððððååååÿÿÿÿÿÿÿÿööööööööððððððððååååÿÿÿÿÿÿÿÿööööööööððððððððððååÿÿÿÿÿÿÿþööööööööðððððððãããããÿþþþþþþþþþöööööööããããããããããã#####þþþþþþþþþþþööööãããããããããäää####¢þþþþþþþþþþþããããããããããäää¢¢¢¢¢¢þþþþþþþþþãããããããääää¢¢¢¢¢¢????????ääää¢¢¢¢¢¢????????äää¢¢¢¢¢¢????????ä¢¢¢¢¢????555555ÙÙ*****FFFFFFFF555@@ÙÙÙÙ*****FFFFFFFFF55\b\b\bÙ\024\024\024\024\024******FFFF¦¦¦¦¦\b\b\b\b|||\024\024\024\024\024******KKK¦¦¦¦¦¦\b\t\t\t\t\t|||||||\024\024\024\024*****KKKKKKKKKK\t\t\t\t\t\t||||||||\024\024\024§§§§§KKKKKKKKKK\t\t\t\t\t\t||||||||\024\024\024§§§§§LLLLLLLLLK\t\t\t\t\t\t\n|||||||}ÓÓ§§§§¨LLLLLLLLLL111\n\n\n\nqqqqqqÓÓÓÓ¨¨¨¨¨LLLLLLLLLdddddd\n\nqqqqqqÓÓÓÓ¨¨¨¨¨SSSSSSSSSdddddddqqqqqqqÓÓÓÓ¨¨¨¨¨SSSSSSSSS_______kkkkkkkÓÓÓÓ®®®®®®SSSSSSSS_______kkkkkkkkpoo®®®®®®®SSSSSSS______``kkkkkkkoÆÆ®®®®®®®®®SSS_____``````kkkkkÆÆÆÆ®®®®®®®®­­­­­­_`````````ccllÄÄÄÄ®®®®®®­­­­­­­­­````````ccclÄÄÄÄÄ®®®®®­­­­­­­­­¸¸¸¸¸.cccccclÄÄÄÄÄ®®­­­­­­­­­­­¸¸¸¸¸¸......cÄÄÄÄÄÄ­­­­­­­­­­­­¸¸¸¸¸¸¸.......ÄÄÄÄÄÄÿÿÿÿÿÿÿÿ\031\031\031\031\031\031\031ðððððððððååååÿÿÿÿÿÿÿÿööööööööðððððððððåååÿÿÿÿÿÿÿÿööööööööðððððððððåååÿÿÿÿÿÿÿÿööööööööððððððððððîîÿÿÿÿÿÿÿþööööööööððððððððãããîÿþþþþþþþþþöööööööããããããããããä####þþþþþþþþþþþþööööãããããããããäää¢¢¢¢¢þþþþþþþþþþþþ\032ãããããããããääää¢¢¢¢¢¢þþþþþþþþþãããããäääää¢¢¢¢¢¢????????ääää¢¢¢¢¢¢????????äää¢¢¢¢¢¢????????5ä¡¡¡¡¡???5555555æ¡¡¡¡¡FFFFF55555@@@ææ*****FFFFFF555@@@@\024\024\024\024\024*****FFFFFFFF@@@@@||\024\024\024\024§§§§§§KKKKKK¦¦¦\t\t\t\t\t\t||||||||\024\024\024§§§§§§KKKKKKKKK\t\t\t\t\t\t\t||||||||\024\024§§§§§§LKKKKKKKK\t\t\t\t\t\t\t||||||||çç§§§§§§LLLLLLLL11111\n\n\n\n|||||}}}}¨¨¨¨¨¨LLLLLLLL11111\n\n\n\nqqqq}}}ÓÓ¨¨¨¨¨¨LLLLLLLLL111\n\n\n\n\nqqqq}}ÓÓÓ¨¨¨¨¨¨SSSSSSSSSddddd\nqqqqppppÓÓÓ¨¨¨¨¨¨SSSSSSSS_______kkkkpppppÓÓ¨®®®®®SSSSSSSS_______kkkkkkooooo®®®®®®®®SSSSSS_____````kkkkooooo®®®®®®®®®SSS_____``````cccllllll®®®®®®®®­­­­­­`````````cccllllÄÄ®®®®®®­­­­­­­­­```````cccclllÄÄÄ®®®®®­­­­­­­­­­¸`....cccccllÄÄÄÄ®®­­­­­­­­­­­­­¸.........bbÄÄÄÄÄ­­­­­­­­­­­­­­¸¸..........bbÄÄÄÄýýýýýýýýööööööïïïïïïïïïïïïåÿÿÿÿÿÿÿöööööööööïïïïïïïïïïåÿÿÿÿÿÿÿöööööööööðïïïïïïïïîîÿÿÿÿÿÿÿöööööööööðïïïïïïïîîîÿÿÿÿÿþþöööööööööððððððîîîîîþþþþþþþþöööööööööðããããîîîîî¢¢¢ þþþþþþþþþþþþööö\032\032\032\032\032\032\032\032äääää¢¢¢¢¢þþþþþþþþþþþþ\032\032\032\032\032\032\032\032\032\032äääää¢¢¢¢¢¢þþþþþþþþþ\032\032\032\032\032\032äääää¢¢¢¢¢¢ ???????äääää¢¢¢¢¢¢????????ääää¡¡¡¡¢¢??55555555ææææ¡¡¡¡¡¡?555555555@ææææ¡¡¡¡¡¡F5555555@@@@æææææ¡¡¡$$$$F5555@@@@@@æææææ§§§$$$$FFFF@@@@@@@ççç§§§§§$$$KKKK@@@@@\t\tççç§§§§§§$KKKKKKK\t\t\t\t\t\t\tççç§§§§§§LLLLLLLL1111111ççç§§§§§§LLLLLLLL111111\n\n\n}}}}ç¨¨¨¨¨¨LLLLLLLL11111\n\n\n\n\n}}}}}}}}¨¨¨¨¨¨LLLLLLLMMM11\n\n\n\n\n\013p}}}}}}}¨¨¨¨¨¨)SSSSMMMMMMM\n\013\013\013\013\013ppppp}}}¨¨¨¨¨¨)SSSSMMMMRRRRRR\013\013\013pppppooo¨¨¨¨¨))SSSSSSMRRRRRRRR\013kpooooooo®®®®®®®®SSSSSRRRRRRR````oooooooo®®®®®®®®®®SSRRRRR``````cccllllll®®®®®®®­­­­­­­````````ccccllllll®®®®®®­­­­­­­­­``````cccccllllll®®®®®­­­­­­­­­­`.....ccccbbllllÄ®®­­­­­­­­­­­­­.........bbbbbÄÄÄ­­­­­­­­­­­­­­­...........bbb\023\023\023ýýýýýýýý÷÷÷÷÷÷÷ïïïïïïïïïïïïïýýýýýýýööööööööïïïïïïïïïïïïýýýýýýýööööööööïïïïïïïïïïîîýýýýýýýööööööööïïïïïïïïïîîîýýýýýööööööööööïïïïïïîîîîîþþþþþþþþöööööööö\032\032\032\032\032îîîîîî¢¢      þþþþþþþööö\032\032\032\032\032\032\032\032\032îîîîî¢¢¢¢       þþþþþþ\032\032\032\032\032\032\032\032\032\032\032ääää¢¢¢¢¢       þþþþ\032\032\032\032\032\032\032\032\032ääää¢¢¢¢¢        \032\032\032\032ääää¡¡¡¢¢¢      5\032æææææ¡¡¡¡¡¢¢55555555ææææææ¡¡¡¡¡¡555555555@@ææææææ¡¡¡¡¡$$555555@@@@@ææææææ¡¡$$$$$$$555@@@@@@æææææ§§$$$$$$$$$@@@@@@@ççç§§§§$$$$$$$$666666çççç§§§§§$$$$$$$$6611111çççç§§§§§§LLLLLLLL1111111çççç§§§§§§LLLLLLLL111111\n\n\n}}ççç¨¨¨¨¨¨LLLLLLLLM1111\n\n\n\n//}}}}}}}¨¨¨¨¨¨)LLLLLMMMMM1\n\n\n\n\013\013\013}}}}}}Õ¨¨¨¨¨))))))MMMMMMM\n\013\013\013\013\013\013pppp}}Õ¨¨¨¨¨))))))MMMMMRRRR\013\013\013\013\013ppppooÕ¨¨¨¨))))))))MMRRRRRRR\013\013\013pooooooo®®®®®))))))))RRRRRRR````ooooooon®®®®®®®®®)))RRRRR``````cccllllnn®®®®®®®­­­­­­­````````ccccllllll®®®®®­­­­­­­­­­``````cccccllllll®®®®­­­­­­­­­­­`.....cbbbbblllll®®­­­­­­­­­­­­­.........bbbbb\023\023\023¬¬¬­­­­­­­­­­­­...........bb\023\023\023\023ýýýýýýýý÷÷÷÷÷÷÷÷÷ïïïïïïïïïïïïýýýýýýýý÷÷÷÷÷÷÷÷ïïïïïïïïïïïïýýýýýýýý÷öööööööïïïïïïïïïïîîýýýýýýýýööööööööïïïïïïïïïîîîýýýýýýýööööööööïïïïïïïîîîîî      þööööööööö\032\032\032\032\032îîîîîî¢¢            öööö\032\032\032\032\032\032\032\032\032îîîîî¢¢¢           þþ\032\032\032\032\032\032\032\032\032\032\032\032îîîî¢¢¢¢          \032\032\032\032\032\032\032\032\032ääää¡¡¡¢         \032\032\032\032\032\032\032ääää¡¡¡¡¡        \032æææææ¡¡¡¡¡¡¡5555555ææææææ¡¡¡¡¡¡¡555555@@ææææææ¡¡¡¡¡$$$555@@@@@@@ææææææ¡¡$$$$$$$$$6@@@@@@æææææ§§$$$$$$$$$66666@@ççç§§§§$$$$$$$$66666AAçççç§§§§$$$$$$$$EEAAAAAAçççç§§§§§§$LLLLEEEEEE111çççç§§§§§§LLLLLLEEEE1111\n\n}çççç¨¨¨¨¨¨LLLLLLEEEE1111\n/////}}}}}}¨¨¨¨¨¨))LLLMMMMMMM1\n\013\013////}}}}ÕÕ¨¨¨¨¨))))))MMMMMMM0\013\013\013\013\013//pp}ÕÕÕ¨¨¨¨¨))))))MMMMMRRRR\013\013\013\013\013pppoÕÕÕ¨¨¨)))))))))MMRRRRRRR\013\013\013\foooooon®®®))))))))))RRRRRRRR``\f\fooonnnn®®®®®)))))))RRRRRRR```cccclnnnnn®®®®®®­­­­­­­RR````QQcccccllllnn®®®®­­­­­­­­­­```QQQQcccbbbllllm®®®¬¬¬¬¬¬¬¬¬¬­­QQQQQQcbbbbbbllmm®¬¬¬¬¬¬¬¬¬¬¬¬¬­.........bbbb\023\023\023\023¬¬¬¬¬¬¬¬¬¬¬¬¬¬¬..........bbm\023\023\023\023ýýýýýýýýý÷÷÷÷÷÷÷÷÷ïïïïïïïïïïïïýýýýýýýý÷÷÷÷÷÷÷÷÷ïïïïïïïïïïïïýýýýýýýýý÷÷÷÷÷÷÷÷ïïïïïïïïïïíîýýýýýýýýýýööööööïïïïïïïïïïîîîýýýýýýýýýööööööïïïïïïïïîîîîî        öööööö\032\032\032\032\032\032\032îîîîîî            öö\032\032\032\032\032\032\032\032\032\032îîîîî¢¢              \032\032\032\032\032\032\032\032\032\032\032\032îîîî¢¢¢           \032\032\032\032\032\032\032\032\032\032\032\032îî¡¡¡¡         \032\032\032\032\032\032\032\032\032æææ¡¡¡¡¡   \032\032æææææ¡¡¡¡¡¡ææææææ¡¡¡¡¡¡@@@@ææææææ¡¡¡¡¡$$$$$66666666ææææææ   $$$$$$$$666666æææææ   $$$$$$$$666666çççç§§§§$$$$$$$$6666AAçççç§§§§$$$$$$$$777AAAAAçççç§§§§§$$$$EEEEEEAAAAçççç§§§§§LLLEEEEEEEAAA}çççç¨¨¨¨¨¨¨LLLEEEEEEE000///////}}}}}¨¨¨¨¨¨))))MMMMMM0000\013\013/////}ÕÕÕÕ¨¨¨¨¨))))))MMMMMM00\013\013\013\013////}ÕÕÕÕ¨¨¨¨¨))))))MMMMMNNNN\013\013\013\013\013\f\f\fÕÕÕÕ¨¨¨)))))))))MMNNNNNNN\013\f\f\f\f\f\fnnnn¨))))))))))))NNNNNNNN\f\f\f\f\f\fnnnnn®®))))))))))NNNNNNNQQQQ\f\f\f\fnnnnn®®®®­­­­­­­­NNNNQQQQQQQQcclnnnnn®®¬¬¬¬¬¬¬¬¬¬¬¬`QQQQQQQQbbbbbmmmm®¬¬¬¬¬¬¬¬¬¬¬¬¬¬QQQQQQQbbbbbmmmmm¬¬¬¬¬¬¬¬¬¬¬¬¬¬¬........bbbbmmm\023\023¬¬¬¬¬¬¬¬¬¬¬¬¬¬¬..........bbm\023\023\023\023ýýýýýýýýýý÷÷÷÷÷÷÷÷÷ïïïïïïïïïïïïýýýýýýýýýý÷÷÷÷÷÷÷÷÷ïïïïïïïïïïïíýýýýýýýýý÷÷÷÷÷÷÷÷ïïïïïïïïïíííýýýýýýýýý÷÷÷÷÷ïïïïïïïïïïííîîýýýýýýýýüüüüüüïïïïïïíííîîîî      üüüüüü\032\032\032\032íííííîîîî           üü\032\032\032\032\032\032\032\032\032\032îîîîî              \032\032\032\032\032\032\032\032\032\032\032\032îîîî¡¡          \032\032\032\032\032\032\032\032\032\032\032\032îî¡¡¡¡      \032\032\032\032\032\032\032\032\032æææ¡¡¡¡¡\032\032\032\032\032æææææ¡¡¡¡¡ææææææ¡¡¡   ææææææ      $$$$66666666ææææææ     $$$$$$666666ææææææ    $$$$$$$666666çççç§§§$$$$$$$$777777Aççççç$$$$$$77777AAAAççççç$$$EEEEEEAAAAçççççEEEEEEEEEAAAççççEEEEEEEE00000///////èèèè¨¨¨¨¨))))))MMM000000\013///////ÕÕÕÕ¨¨¨¨))))))))MMM00000\013//////ÕÕÕÕÕ¨¨¨¨))))))))MMMNNNNNN\013\013\f\f\f\f\fÕÕÕÕ¨¨¨)))))))))MMNNNNNNN\f\f\f\f\f\f\fnnnn¨))))))))))))NNNNNNNN\f\f\f\f\f\fnnnnn))))))))))))NNNNNNNQQQQ\f\f\f\fnnnn~®))))©©©©©©©©©NNQQQQQQQQQ\rnn~~~~¬¬¬¬¬¬¬¬¬¬¬¬¬©©QQQQQQQQQbbbmmmmm¬¬¬¬¬¬¬¬¬¬¬¬¬¬¬QQQQQQQQQbbbmmmmm¬¬¬¬¬¬¬¬¬¬¬¬¬¬¬QQ.....bbbbmmmmm\023¬¬¬¬¬¬¬¬¬¬¬¬¬¬¬.........bbmm\023\023\023\023ýýýýýýýýýýý÷÷÷÷÷÷÷÷÷ïïïïïïïïïïïïýýýýýýýýý÷÷÷÷÷÷÷÷÷ïïïïïïïïïïííýýýýýýýý÷÷÷÷÷÷÷÷ïïïïïïïíííííýýýýýýýüüüüüüüïïïïïïíííííííýýýýüüüüüüüüüïïïïííííííîî   üüüüüüüüü\032\032\032íííííííîî        üüüüü\032\032\032\032\032\032\032\032\032íîîîî             üü\032\032\032\032\032\032\032\032\032\032\032îîîî¡¡          \032\032\032\032\032\032\032\032\032\032\032\032îî¡¡¡    \032\032\032\032\032\032\032\032\032æææ¡¡¡¡\032\032\032\032æææææ     æææææææ      æææææææ       $$$6666666ææææææ     $$$$$$666666ææææææ     $$$$$$666666ëëëëë   $$$$$$$$777777Açççç$$$$$$77777AAAAçççç$$$EE7777AAAAççççEEEEEEEEEAAAèèèèEEEEEEE00000èèèè))))))EEE0000000èèèè¨¨¨)))))))))M00000000/////ÕÕÕÕ¨¨¨))))))))))NNNNNNNN\f\f\f\f\f\f\féééé¨¨)))))))))))NNNNNNNN\f\f\f\f\f\f\féééé))))))))))))©NNNNNNNNO\f\f\f\f\f\fnééé)))))))©©©©©©©NNNNOOOOOO\f\r\r\r~~~~ª)))©©©©©©©©©©©©QQQQQQQQ\r\r\r\r~~~~¬¬¬¬¬¬¬¬©©©©©©©©QQQQQQQQQ\r\rmmmmm¬¬¬¬¬¬¬¬¬¬¬¬¬¬¬QQQQQQQQQQmmmmmmm¬¬¬¬¬¬¬¬¬¬¬¬¬¬¬QQQQQQQQQbmmmmmmm¬¬¬¬¬¬¬¬¬¬¬¬¬¬¬¬.....bbbbmmmm\023\023\023ýýýýýýýýýý÷÷÷÷÷÷÷÷÷÷÷ïïïïïïïï\033\033\033ýýýýýýý÷÷÷÷÷÷÷÷÷÷ïïïïïïïïï\033\033\033ýýýýýüüüüüüüüüïïïïííííííííýýýüüüüüüüüüüïïïíííííííííýýüüüüüüüüüüüïïííííííííí  üüüüüüüüüüü\032íííííííííí      üüüüüüü\032\032\032\032\032íííííííî           üüü\032\032\032\032\032\032\032\032\032\032\032îîîî¡     \032\032\032\032\032\032\032\032\032\032\032\032îî¡¡   \032\032\032\032\032\032\032\032\032ææ¡¡\032\032\032\032æææææ     æææææææ      ëëëëëëë       $$$6666666ëëëëëëë      $$$$$66666ëëëëëë      $$$$$777777ëëëëëë    $$$$$$7777777Aëëëë$$$$7777777AAAçççç$777777888AAèèèèEEEEE888888AèèèèEEEEE880000BBèèèè)))EEE000000BBBBBèèèè))))))))))0000000BBBBééé¨)))))))))))NNNNNNDDDDéééé)))))))©©©©©NNNNNDDDD\f\f\fééé)))))©©©©©©©©NNNNDDDD\f\f\f\féééªª)))©©©©©©©©©©NNNOOOOOO\r\r\r\r\r~~~ªªªª©©©©©©©©©©©©OOOOOOOO\r\r\r\r\r~~~ª¬¬¬¬¬©©©©©©©©©©QQQQQQQQ\r\r\r\r\r\177\177\177(¬¬¬¬¬¬¬¬¬¬¬¬¬¬QQQQQQQQQQPmmmaaa(¬¬¬¬¬¬¬¬¬¬¬¬¬¬QQQQQQPPPPPmmaaaa(¬¬¬¬¬¬¬¬¬¬¬¬¬¬¬QQPPPPPPPmmaaaaa\037\037\037\037ýýýýý÷÷÷÷÷÷÷÷÷÷÷÷ïïï\033\033\033\033\033\033\033\033ýýý÷÷÷÷÷÷÷÷÷÷÷÷ïïïí\033\033\033\033\033\033\033ýüüüüüüüüüüüüïííííííííííüüüüüüüüüüüüíííííííííííüüüüüüüüüüüüíííííííííííüüüüüüüüüüüüííííííííííí  üüüüüüüüüüû\032íííííííííí       ûûûûûûûû\032\032\032\032\032\032íííííûûûûûû\032\032\032\032\032\032\032ìììì\032\032\032\032\032\032ììììøøøøøìììì   øøøøëëëëë      ùùùëëëëëë       6666ëëëëëëë        $$$6666ëëëëëëë       $$777777777ëëëëëë      $$$777777777ëëëëë$7777777778A\034\034\034777778888888èèèè7788888888BèèèèE888888899BBBèèè88999999BBBBBèèè0999999BBBBéé©©9NNNDDDDDDéééª©©©©©©©NNDDDDDDDééªª©©©©©©©©©NDDDDDDD\rééªªªªª©©©©©©©©©©©NOOOOOOO\r\r\r\r\r~~~ªªªªª©©©©©©©©©©©OOOOOOOO\r\r\r\r\r\177\177\177ªªªª¬¬©©©©©©©©©©OOOOOOOPPP\r\r\016\177\177\177(((¬¬¬¬¬¬¬¬¬¬¬©QQQQQQPPPPPP\016\016aaa(((¬¬¬¬¬¬¬¬¬¬¬¬QQQQQPPPPPPP\016aaaa(((¬¬¬¬¬¬¬¬¬¬¬¬¬QQPPPPPPPPPaaaaa\037\037\037\037\037\037\037\037÷÷÷÷÷÷÷÷÷÷÷÷÷÷\033\033\033\033\033\033\033\033\033\033\037\037\037\037\037\037üüüüüüüüüüüüüíí\033\033\033\033\033\033\033\033üüüüüüüüüüüüíííííííí\033\033\033üüüüüüüüüüüüíííííííííííüüüüüüüüüüüüíííííííííííüüüüüüüüüüüûûííííííííííüüüüüûûûûûûûûûíííííííííûûûûûûûûûûûøøøííííííûûûûûûûûøøøøøøøììììûûûûûûøøøøøøøøììììûøøøøøøøøøøìììì  øøøøøøøøøøìììì    øùùùùùùùùëëëëë      \036ùùùùùùùùëëëëë        \036\036\036\036\036\036\036ùùùùùùùëëëëë        77777\036\036\036\036\036\036\036\036ùùùùùëëëëëë%%%%% 777777777\036\036\036\036ëëëëëë77788888888\034\034\034\034\0347888888888èèèè888888889Bèèè88888999BBBBBèè8999999BBBBBè&999999:BBBBé&&&:::::DDDDéªª©©©©©©&::DDDDDDéªªªª©©©©©©©©©&DDDDDDD;êªªªªªª©©©©©©©©©©OOOOOOOO\r\r\r\r\r\r\177\177ªªªªªª«©©©©©©©©©OOOOOOOO<\r\r\r\r\177\177\177ªªªªª««««©©©©©©©OOOOOOPPPPP\016\016\016\177\177(((((«««««««««««OOOOPPPPPPP\016\016\016\016a(((((«««««««««««Q''PPPPPPPP\016\016aaa((((((¬¬««««««««''''''PPPPPaaaaa\037\037\037\037\037\037\037\037\037÷÷÷÷÷÷÷÷÷÷÷÷÷\033\033\033\033\033\033\033\033\033\033\037\037\037\037\037\037\037üüüüüüüüüüüüüüíí\033\033\033\033\033\033\033\033\037\037\037\037üüüüüüüüüüüüüííííí\033\033\033\033\033üüüüüüüüüüüüüííííííííííüüüüüüüüüüüüüííííííííííüüüüüüüüüüûûûûíííííííííüüüüûûûûûûûûûûíííííííííûûûûûûûûûûûûøøøøøììììûûûûûûûûûøøøøøøøììììûûûûûûûøøøøøøøøììììøøøøøøøøøøìììì øøøøøøøøøøìììì   øùùùùùùùùùëëëë    \036ùùùùùùùùùëëëë%%%    \036\036\036\036\036\036\036ùùùùùùùùëëëë%%%%%   \036\036\036\036\036\036\036\036\036\036\036ùùùùùùëëëëë%%%%%%%77\036\036\036\036\036\036\036\036\036\036\036\036\036ùù\034\034\034\034\034\034\034%%%%%888\036\036\036\036\036\036\036\036úúúúúú\034\034\034\034\03488888888úúúúúúúúú\034\034\034\03488888899úúúúúúè88899999BBúúú89999999BBBBú&&&99:::::BBª&&&&&&:::::;;êêêªªª©©©&&&&&:D;;;;\035Cêêêªªªªªª©©©©©©©&&&D;;;;;CCCCCêêêªªªªªªª©©©©©©©©©©DD;;;;<<CCCCCªªªªªª««©©©©©©©©©OOOOOP<<<<<<\177\177\177ªªªªª««««««««©©©OOOOOPPPPP<<\016\016\016\177(((((«««««««««««OOO'PPPPPPP\016\016\016\016\016(((((««««««««««««'''''PPPPP\016\016\016\016a((((((««««««««««'''''''''''aaaaa\037\037\037\037\037\037\037\037\037üüüüüüüüüüüüü\033\033\033\033\033\033\033\033\033\033\037\037\037\037\037\037\037\037üüüüüüüüüüüüüüí\033\033\033\033\033\033\033\033\033\037\037\037\037\037\037üüüüüüüüüüüüüüííí\033\033\033\033\033\033\033üüüüüüüüüüüüüüííííííííí\033üüüüüüüüüüüüüûííííííííííüüüüüüüüüüûûûûûíííííííííüüüüûûûûûûûûûûûííííííííûûûûûûûûûûûûûøøøøøììììûûûûûûûûûûøøøøøøøììììûûûûûûûûøøøøøøøøììììøøøøøøøøøøììììøøøøøøøøøøìììì øùùùùùùùùùùììì%%%\036ùùùùùùùùùëëëë%%%%%%\036\036\036\036\036\036\036ùùùùùùùùëëëë%%%%%%%\036\036\036\036\036\036\036\036\036\036\036ùùùùùù\034\034\034\034\034%%%%%%%%\036\036\036\036\036\036\036\036\036\036\036\036\036\036\036ùù\034\034\034\034\034\034\034%%%%%%\036\036\036\036\036\036\036\036\036\036\036úúúúú\034\034\034\034\034\034%%%8888888úúúúúúúúú\034\034\034\03488999999úúúúúúú89999999úúúúúú&9999999:úúúú&&&&&:::::\035\035\035\035êª&&&&&&&::::;\035\035\035\035\035êêêêªªª©©&&&&&&&;;;;;\035\035\035\035êêêêªªªªªª©©©©©©&&&&;;;;;;CCCCCêêêªªªªªªª©©©©©©©©©©;;;;;;<<CCCCªªªªªª««««««©©©©©O;;PP<<<<<<ªªªªª«««««««««©©©OOPPPPP<<<<\016\016\016(((((«««««««««««O''''PPPPPP\016\016\016\016\016(((((««««««««««««'''''''''P\016\016\016\017\017((((((««««««««««''''''''''''\017\017\017\017\037\037\037\037\037\037\037\037\037üüüüüüüüüüüü\033\033\033\033\033\033\033\033\033\033\033\037\037\037\037\037\037\037\037üüüüüüüüüüüüüü\033\033\033\033\033\033\033\033\033\033\037\037\037\037\037\037\037\037üüüüüüüüüüüüüüí\033\033\033\033\033\033\033\033\033\037\037\037\037\037\037\037üüüüüüüüüüüüüüíí\033\033\033\033\033\033\033\033üüüüüüüüüüüüûûûíííííííííüüüüüüûûûûûûûûûûííííííííüüüûûûûûûûûûûûûûíííííííûûûûûûûûûûûûûøøøøøììììûûûûûûûûûûøøøøøøøììììûûûûûûûûøøøøøøøøììììøøøøøøøøøøììììøøøøøøøøøøììììøùùùùùùùùùùììì%%%\036ùùùùùùùùùùëëë%%%%%%\036\036\036\036\036\036\036ùùùùùùùù\034\034\034\034%%%%%%%\036\036\036\036\036\036\036\036\036\036\036ùùùùùù\034\034\034\034\034%%%%%%%%\036\036\036\036\036\036\036\036\036\036\036\036\036\036\036ùù\034\034\034\034\034\034\034%%%%%%\036\036\036\036\036\036\036\036\036\036\036úúúúú\034\034\034\034\034\034%%%\036\036\036\036\036\036\036úúúúúúúúú\034\034\034\034%999999úúúúúúúú999999úúúúúúúú&&99:::::úúúúú&&&&&&::::\035\035\035\035\035\035êêª&&&&&&&:::;;\035\035\035\035\035êêêêªªª&&&&&&&&;;;;\035\035\035\035\035êêêêªªªªªª©©&&&&&&;;;;;;CCCCCêêêªªªªªªª««©©©©©©©&;;;;;;<CCCCCªªªªªª««««««««©©©;;;PP<<<<<<ªªªªª««««««««««©©©PPPPP<<<<<(((((««««««««««««'''''PPP<<<\016\016\016\016(((((««««««««««««''''''''''\016\017\017\017\017((((((««««««««««''''''''''''\017\017\017\017".getBytes(StandardCharsets.ISO_8859_1)
            ;


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
     * Constructs a default PaletteReducer that uses the DawnBringer Aurora palette.
     */
    public PaletteReducer() {
        exact(AURORA, ENCODED_AURORA);
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
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        exact(rgbaPalette);
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
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        exact(colorPalette);
    }

    /**
     * Constructs a PaletteReducer that uses the given array of Color objects as a palette (see {@link #exact(Color[])}
     * for more info).
     *
     * @param colorPalette an array of Color objects to use as a palette
     */
    public PaletteReducer(Color[] colorPalette, int limit) {
        if(colorPalette == null)
        {
            exact(AURORA, ENCODED_AURORA);
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
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        analyze(pixmap);
    }

    /**
     * Constructs a PaletteReducer that analyzes the given Pixmaps for color count and frequency to generate a palette
     * (see {@link #analyze(Array)} )} for more info).
     *
     * @param pixmaps an Array of Pixmap to analyze in detail to produce a palette
     */
    public PaletteReducer(Array<Pixmap> pixmaps) {
        if(pixmaps == null)
        {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        analyze(pixmaps);
    }
    /**
     * Constructs a PaletteReducer that uses the given array of RGBA8888 ints as a palette (see {@link #exact(int[])}
     * for more info) and an encoded String to use to look up pre-loaded color data.
     *
     * @param palette an array of RGBA8888 ints to use as a palette
     * @param preload an ISO-8859-1-encoded String containing preload data
     */
    public PaletteReducer(int[] palette, byte[] preload)
    {
        exact(palette, preload);
    }
    /**
     * Constructs a PaletteReducer that analyzes the given Pixmap for color count and frequency to generate a palette
     * (see {@link #analyze(Pixmap, int)} for more info).
     *
     * @param pixmap    a Pixmap to analyze in detail to produce a palette
     * @param threshold the minimum difference between colors required to put them in the palette (default 400)
     */
    public PaletteReducer(Pixmap pixmap, int threshold) {
        analyze(pixmap, threshold);
    }

//    /**
//     * Color difference metric; returns large numbers even for smallish differences.
//     * If this returns 250 or more, the colors may be perceptibly different; 500 or more almost guarantees it.
//     *
//     * @param color1 an RGBA8888 color as an int
//     * @param color2 an RGBA8888 color as an int
//     * @return the difference between the given colors, as a positive double
//     */
//    public static double difference(int color1, int color2) {
//        if(((color1 ^ color2) & 0x80) == 0x80) return Double.POSITIVE_INFINITY;
//        return //Math.sqrt
//                 (RGB_POWERS[Math.abs((color1 >>> 24) - (color2 >>> 24))]
//                + RGB_POWERS[Math.abs((color1 >>> 16 & 0xFF) - (color2 >>> 16 & 0xFF))]
//                + RGB_POWERS[Math.abs((color1 >>> 8 & 0xFF) - (color2 >>> 8 & 0xFF))]) * 0x5p-7;
//    }
//
//
//    /**
//     * Color difference metric; returns large numbers even for smallish differences.
//     * If this returns 250 or more, the colors may be perceptibly different; 500 or more almost guarantees it.
//     *
//     * @param color1 an RGBA8888 color as an int
//     * @param r2     red value from 0 to 255, inclusive
//     * @param g2     green value from 0 to 255, inclusive
//     * @param b2     blue value from 0 to 255, inclusive
//     * @return the difference between the given colors, as a positive double
//     */
//    public static double difference(int color1, int r2, int g2, int b2) {
//        if((color1 & 0x80) == 0) return Double.POSITIVE_INFINITY;
//        return //Math.sqrt
//                 (RGB_POWERS[Math.abs((color1 >>> 24) - r2)]
//                + RGB_POWERS[Math.abs((color1 >>> 16 & 0xFF) - g2)]
//                + RGB_POWERS[Math.abs((color1 >>> 8 & 0xFF) - b2)]) * 0x5p-7;
//    }
//
//    /**
//     * Color difference metric; returns large numbers even for smallish differences.
//     * If this returns 250 or more, the colors may be perceptibly different; 500 or more almost guarantees it.
//     *
//     * @param r1 red value from 0 to 255, inclusive
//     * @param g1 green value from 0 to 255, inclusive
//     * @param b1 blue value from 0 to 255, inclusive
//     * @param r2 red value from 0 to 255, inclusive
//     * @param g2 green value from 0 to 255, inclusive
//     * @param b2 blue value from 0 to 255, inclusive
//     * @return the difference between the given colors, as a positive double
//     */
//    public static double difference(final int r1, final int g1, final int b1, final int r2, final int g2, final int b2) {
//        return //Math.sqrt
//                 (RGB_POWERS[Math.abs(r1 - r2)]
//                + RGB_POWERS[Math.abs(g1 - g2)]
//                + RGB_POWERS[Math.abs(b1 - b2)]) * 0x5p-7;
//    }

    public double difference(int color1, int color2) {
        if (((color1 ^ color2) & 0x80) == 0x80) return Double.POSITIVE_INFINITY;
        final int indexA = (color1 >>> 17 & 0x7C00) | (color1 >>> 14 & 0x3E0) | (color1 >>> 11 & 0x1F),
                indexB = (color2 >>> 17 & 0x7C00) | (color2 >>> 14 & 0x3E0) | (color2 >>> 11 & 0x1F);
        final double
                L = LAB[0][indexA] - LAB[0][indexB],
                A = LAB[1][indexA] - LAB[1][indexB],
                B = LAB[2][indexA] - LAB[2][indexB];
        return L * L * 11.0 + A * A * 1.6 + B * B;
    }

    public double difference(int color1, int r2, int g2, int b2) {
        if((color1 & 0x80) == 0) return Double.POSITIVE_INFINITY;
        final int indexA = (color1 >>> 17 & 0x7C00) | (color1 >>> 14 & 0x3E0) | (color1 >>> 11 & 0x1F),
                indexB = (r2 << 7 & 0x7C00) | (g2 << 2 & 0x3E0) | (b2 >>> 3);
        final double
                L = LAB[0][indexA] - LAB[0][indexB],
                A = LAB[1][indexA] - LAB[1][indexB],
                B = LAB[2][indexA] - LAB[2][indexB];
        return L * L * 11.0 + A * A * 1.6 + B * B;
    }

    public double difference(int r1, int g1, int b1, int r2, int g2, int b2) {
        final int indexA = (r1 << 7 & 0x7C00) | (g1 << 2 & 0x3E0) | (b1 >>> 3),
                indexB = (r2 << 7 & 0x7C00) | (g2 << 2 & 0x3E0) | (b2 >>> 3);
        final double
                L = LAB[0][indexA] - LAB[0][indexB],
                A = LAB[1][indexA] - LAB[1][indexB],
                B = LAB[2][indexA] - LAB[2][indexB];
        return L * L * 11.0 + A * A * 1.6 + B * B;
    }

    /**
     * Builds the palette information this PNG8 stores from the RGBA8888 ints in {@code rgbaPalette}, up to 256 colors.
     * Alpha is not preserved except for the first item in rgbaPalette, and only if it is {@code 0} (fully transparent
     * black); otherwise all items are treated as opaque. If rgbaPalette is null, empty, or only has one color, then
     * this defaults to DawnBringer's Aurora palette with 256 hand-chosen colors (including transparent).
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
     * limit is less than 2, then this defaults to DawnBringer's Aurora palette with 256 hand-chosen colors (including
     * transparent).
     *
     * @param rgbaPalette an array of RGBA8888 ints; all will be used up to 256 items or the length of the array
     * @param limit       a limit on how many int items to use from rgbaPalette; useful if rgbaPalette is from an IntArray
     */
    public void exact(int[] rgbaPalette, int limit) {
        if (rgbaPalette == null || rgbaPalette.length < 2 || limit < 2) {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        final int plen = Math.min(Math.min(256, limit), rgbaPalette.length);
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
                        dist = 0x7FFFFFFF;
                        for (int i = 1; i < plen; i++) {
                            if (dist > (dist = Math.min(dist, difference(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
        calculateGamma();
    }

    /**
     * Builds the palette information this PaletteReducer stores from the given array of RGBA8888 ints as a palette (see
     * {@link #exact(int[])} for more info) and an encoded byte array to use to look up pre-loaded color data. The
     * encoded byte array can be copied out of the {@link #paletteMapping} of an existing PaletteReducer, or just as
     * likely you can use {@link #ENCODED_AURORA} as a nice default. There's slightly more startup time spent when
     * initially calling {@link #exact(int[])}, but it will produce the same result. You can store the paletteMapping
     * from that PaletteReducer once, however you want to store it, and send it back to this on later runs.
     *
     * @param palette an array of RGBA8888 ints to use as a palette
     * @param preload a byte array with exactly 32768 (or 0x8000) items, containing {@link #paletteMapping} data
     */
    public void exact(int[] palette, byte[] preload)
    {
        if(palette == null || preload == null)
        {
            System.arraycopy(AURORA, 0,  paletteArray, 0, 256);
            System.arraycopy(ENCODED_AURORA, 0,  paletteMapping, 0, 0x8000);

            calculateGamma();
            return;
        }
        System.arraycopy(palette, 0,  paletteArray, 0, Math.min(256, palette.length));
        System.arraycopy(preload, 0,  paletteMapping, 0, 0x8000);

        calculateGamma();
    }

    /**
     * Builds the palette information this PaletteReducer stores from the Color objects in {@code colorPalette}, up to
     * 256 colors.
     * Alpha is not preserved except for the first item in colorPalette, and only if its r, g, b, and a values are all
     * 0f (fully transparent black); otherwise all items are treated as opaque. If rgbaPalette is null, empty, or only
     * has one color, then this defaults to DawnBringer's Aurora palette with 256 hand-chosen colors (including
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
     * one color, or limit is less than 2, then this defaults to DawnBringer's Aurora palette with 256 hand-chosen
     * colors (including transparent).
     *
     * @param colorPalette an array of Color objects; all will be used up to 256 items, limit, or the length of the array
     * @param limit        a limit on how many Color items to use from colorPalette; useful if colorPalette is from an Array
     */
    public void exact(Color[] colorPalette, int limit) {
        if (colorPalette == null || colorPalette.length < 2 || limit < 2) {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        final int plen = Math.min(Math.min(256, colorPalette.length), limit);
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
                            if (dist > (dist = Math.min(dist, difference(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
        calculateGamma();
    }
    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most 256 colors if there are
     * too many colors to store in a PNG-8 palette. If there are 256 or less colors, this uses the exact colors
     * (although with at most one transparent color, and no alpha for other colors); if there are more than 256 colors
     * or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even if the image has no
     * transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that
     * aren't exact, and dithering works better when the palette can choose colors that are sufficiently different, this
     * uses a threshold value to determine whether it should permit a less-common color into the palette, and if the
     * second color is different enough (as measured by {@link #difference(int, int)}) by a value of at least 400, it is
     * allowed in the palette, otherwise it is kept out for being too similar to existing colors. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} field or can be used directly to {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmap a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     */
    public void analyze(Pixmap pixmap) {
        analyze(pixmap, 400);
    }

    private static final Comparator<IntIntMap.Entry> entryComparator = new Comparator<IntIntMap.Entry>() {
        @Override
        public int compare(IntIntMap.Entry o1, IntIntMap.Entry o2) {
            return o2.value - o1.value;
        }
    };


    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most 256 colors if there are
     * too many colors to store in a PNG-8 palette. If there are 256 or less colors, this uses the exact colors
     * (although with at most one transparent color, and no alpha for other colors); if there are more than 256 colors
     * or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even if the image has no
     * transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that
     * aren't exact, and dithering works better when the palette can choose colors that are sufficiently different, this
     * takes a threshold value to determine whether it should permit a less-common color into the palette, and if the
     * second color is different enough (as measured by {@link #difference(int, int)}) by a value of at least
     * {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. The threshold is usually between 250 and 1000, and 400 is a good default. This doesn't return a value but
     * instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} field or can be used directly to {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     * @param threshold a minimum color difference as produced by {@link #difference(int, int)}; usually between 250 and 1000, 400 is a good default
     */
    public void analyze(Pixmap pixmap, int threshold) {
        analyze(pixmap, threshold, 256);
    }
    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most {@code limit} colors.
     * If there are {@code limit} or less colors, this uses the exact colors (although with at most one transparent
     * color, and no alpha for other colors); if there are more than {@code limit} colors or any colors have 50% or less
     * alpha, it will reserve a palette entry for transparent (even if the image has no transparency). Because calling
     * {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that aren't exact, and dithering
     * works better when the palette can choose colors that are sufficiently different, this takes a threshold value to
     * determine whether it should permit a less-common color into the palette, and if the second color is different
     * enough (as measured by {@link #difference(int, int)}) by a value of at least {@code threshold}, it is allowed in
     * the palette, otherwise it is kept out for being too similar to existing colors. The threshold is usually between
     * 250 and 1000, and 400 is a good default. This doesn't return a value but instead stores the palette info in this
     * object; a PaletteReducer can be assigned to the {@link PNG8#palette} or {@link AnimatedGif#palette}
     * fields, or can be used directly to {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     * @param threshold a minimum color difference as produced by {@link #difference(int, int)}; usually between 250 and 1000, 400 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyze(Pixmap pixmap, int threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntIntMap counts = new IntIntMap(limit);
        int hasTransparent = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                color = pixmap.getPixel(x, y);
                if ((color & 0x80) != 0) {
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    counts.getAndIncrement(color, 0, 1);
                } else {
                    hasTransparent = 1;
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
        if (cs + hasTransparent <= limit) {
            int i = hasTransparent;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            for (; i < limit && c < cs;) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    if (difference(color, paletteArray[j]) < threshold)
                        continue PER_BEST;
                }
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
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
                        for (int i = 1; i < limit; i++) {
                            if (dist > (dist = Math.min(dist, difference(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
        calculateGamma();
    }

    /**
     * Analyzes all of the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most 256 colors. If there are 256 or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * 256 colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #difference(int, int)}) by a
     * value of at least 400, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. This doesn't return a value but instead stores the palette info in this object; a PaletteReducer can be
     * assigned to the {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     */
    public void analyze(Array<Pixmap> pixmaps){
        analyze(pixmaps.toArray(Pixmap.class), pixmaps.size, 400, 256);
    }

    /**
     * Analyzes all of the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most 256 colors. If there are 256 or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * 256 colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #difference(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 250 and 1000, and 400 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param threshold a minimum color difference as produced by {@link #difference(int, int)}; usually between 250 and 1000, 400 is a good default
     */
    public void analyze(Array<Pixmap> pixmaps, int threshold){
        analyze(pixmaps.toArray(Pixmap.class), pixmaps.size, threshold, 256);
    }

    /**
     * Analyzes all of the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. If there are {@code limit} or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * {@code limit} colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #difference(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 250 and 1000, and 400 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param threshold a minimum color difference as produced by {@link #difference(int, int)}; usually between 250 and 1000, 400 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyze(Array<Pixmap> pixmaps, int threshold, int limit){
        analyze(pixmaps.toArray(Pixmap.class), pixmaps.size, threshold, limit);
    }
    /**
     * Analyzes all of the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. If there are {@code limit} or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * {@code limit} colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #difference(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 250 and 1000, and 400 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param pixmapCount the maximum number of Pixmap entries in pixmaps to use
     * @param threshold a minimum color difference as produced by {@link #difference(int, int)}; usually between 250 and 1000, 400 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyze(Pixmap[] pixmaps, int pixmapCount, int threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        IntIntMap counts = new IntIntMap(limit);
        int hasTransparent = 0;
        int[] reds = new int[limit], greens = new int[limit], blues = new int[limit];
        for (int i = 0; i < pixmapCount && i < pixmaps.length; i++) {
            Pixmap pixmap = pixmaps[i];
            final int width = pixmap.getWidth(), height = pixmap.getHeight();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    color = pixmap.getPixel(x, y);
                    if ((color & 0x80) != 0) {
                        color |= (color >>> 5 & 0x07070700) | 0xFF;
                        counts.getAndIncrement(color, 0, 1);
                    } else {
                        hasTransparent = 1;
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
        if (cs + hasTransparent <= limit) {
            int i = hasTransparent;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                reds[i] = color >>> 24;
                greens[i] = color >>> 16 & 255;
                blues[i] = color >>> 8 & 255;
                i++;
            }
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            for (; i < limit && c < cs;) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    if (difference(color, paletteArray[j]) < threshold)
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
//        for (int i = 0; i < 256; i++) {
//            System.out.printf("0x%08X, ", paletteArray[i]);
//            if((i& 7) == 7) System.out.println();
//        }
//        System.out.println("\n");
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
                        for (int i = 1; i < limit; i++) {
                            if (dist > (dist = Math.min(dist, difference(reds[i], greens[i], blues[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
        calculateGamma();
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
        this.ditherStrength = Math.max(0f, 0.5f * ditherStrength);
        this.halfDitherStrength = 0.5f * this.ditherStrength;
        calculateGamma();
    }
    
    void calculateGamma(){
        double gamma = 2.0 - this.ditherStrength * 1.666;
        for (int i = 0; i < 256; i++) {
            int color = paletteArray[i];
            double r = Math.pow((color >>> 24) / 255.0, gamma);
            double g = Math.pow((color >>> 16 & 0xFF) / 255.0, gamma);
            double b = Math.pow((color >>>  8 & 0xFF) / 255.0, gamma);
            int a = color & 0xFF;
            gammaArray[i] = (int)(r * 255.999) << 24 | (int)(g * 255.999) << 16 | (int)(b * 255.999) << 8 | a;
        }
    }

    /**
     * Modifies the given Pixmap so it only uses colors present in this PaletteReducer, dithering when it can
     * using Floyd-Steinberg (this merely delegates to {@link #reduceFloydSteinberg(Pixmap)}).
     * If you want to reduce the colors in a Pixmap based on what it currently contains, call
     * {@link #analyze(Pixmap)} with {@code pixmap} as its argument, then call this method with the same
     * Pixmap. You may instead want to use a known palette instead of one computed from a Pixmap;
     * {@link #exact(int[])} is the tool for that job.
     * <p>
     * This method is not incredibly fast because of the extra calculations it has to do for dithering, but if you can
     * compute the PaletteReducer once and reuse it, that will save some time.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduce (Pixmap pixmap) {
        return reduceFloydSteinberg(pixmap);
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
                if ((color & 0x80) == 0 && hasTransparent)
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
     * Modifies the given Pixmap so it only uses colors present in this PaletteReducer, dithering when it can using
     * Sierra Lite dithering instead of the Floyd-Steinberg dithering that {@link #reduce(Pixmap)} uses.
     * If you want to reduce the colors in a Pixmap based on what it currently contains, call
     * {@link #analyze(Pixmap)} with {@code pixmap} as its argument, then call this method with the same
     * Pixmap. You may instead want to use a known palette instead of one computed from a Pixmap;
     * {@link #exact(int[])} is the tool for that job.
     * <p>
     * This method is meant to be a little faster than Floyd-Steinberg, but the quality isn't quite as good sometimes.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduceSierraLite (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        byte[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedBytes == null) {
            curErrorRed = (curErrorRedBytes = new ByteArray(lineLen)).items;
            nextErrorRed = (nextErrorRedBytes = new ByteArray(lineLen)).items;
            curErrorGreen = (curErrorGreenBytes = new ByteArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenBytes = new ByteArray(lineLen)).items;
            curErrorBlue = (curErrorBlueBytes = new ByteArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueBytes = new ByteArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedBytes.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedBytes.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenBytes.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenBytes.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueBytes.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueBytes.ensureCapacity(lineLen);
            for (int i = 0; i < lineLen; i++) {
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }

        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used, rdiff, gdiff, bdiff;
        byte er, eg, eb, paletteIndex;
        for (int y = 0; y < h; y++) {
            int ny = y + 1;
            for (int i = 0; i < lineLen; i++) {
                curErrorRed[i] = nextErrorRed[i];
                curErrorGreen[i] = nextErrorGreen[i];
                curErrorBlue[i] = nextErrorBlue[i];
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y) & 0xF8F8F880;
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    er = curErrorRed[px];
                    eg = curErrorGreen[px];
                    eb = curErrorBlue[px];
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    int rr = MathUtils.clamp(((color >>> 24)       ) + (er), 0, 0xFF);
                    int gg = MathUtils.clamp(((color >>> 16) & 0xFF) + (eg), 0, 0xFF);
                    int bb = MathUtils.clamp(((color >>> 8)  & 0xFF) + (eb), 0, 0xFF);
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (color>>>24)-    (used>>>24);
                    gdiff = (color>>>16&255)-(used>>>16&255);
                    bdiff = (color>>>8&255)- (used>>>8&255);
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
     * Modifies the given Pixmap so it only uses colors present in this PaletteReducer, dithering when it can using the
     * commonly-used Floyd-Steinberg dithering. If you want to reduce the colors in a Pixmap based on what it currently
     * contains, call {@link #analyze(Pixmap)} with {@code pixmap} as its argument, then call this method with the same
     * Pixmap. You may instead want to use a known palette instead of one computed from a Pixmap;
     * {@link #exact(int[])} is the tool for that job.
     * <p>
     * This method is not incredibly fast because of the extra calculations it has to do for dithering, but if you can
     * compute the PaletteReducer once and reuse it, that will save some time.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduceFloydSteinberg (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        byte[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedBytes == null) {
            curErrorRed = (curErrorRedBytes = new ByteArray(lineLen)).items;
            nextErrorRed = (nextErrorRedBytes = new ByteArray(lineLen)).items;
            curErrorGreen = (curErrorGreenBytes = new ByteArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenBytes = new ByteArray(lineLen)).items;
            curErrorBlue = (curErrorBlueBytes = new ByteArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueBytes = new ByteArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedBytes.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedBytes.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenBytes.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenBytes.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueBytes.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueBytes.ensureCapacity(lineLen);
            for (int i = 0; i < lineLen; i++) {
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            } 
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used, rdiff, gdiff, bdiff;
        byte er, eg, eb, paletteIndex;
        float w1 = ditherStrength * 0.125f, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;
        for (int y = 0; y < h; y++) {
            int ny = y + 1;
            for (int i = 0; i < lineLen; i++) {
                curErrorRed[i] = nextErrorRed[i];
                curErrorGreen[i] = nextErrorGreen[i];
                curErrorBlue[i] = nextErrorBlue[i];
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y) & 0xF8F8F880;
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    er = curErrorRed[px];
                    eg = curErrorGreen[px];
                    eb = curErrorBlue[px];
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    int rr = MathUtils.clamp(((color >>> 24)       ) + (er), 0, 0xFF);
                    int gg = MathUtils.clamp(((color >>> 16) & 0xFF) + (eg), 0, 0xFF);
                    int bb = MathUtils.clamp(((color >>> 8)  & 0xFF) + (eb), 0, 0xFF);
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (color>>>24)-    (used>>>24);
                    gdiff = (color>>>16&255)-(used>>>16&255);
                    bdiff = (color>>>8&255)- (used>>>8&255);
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
    
    public Pixmap reduceJimenez(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float pos, adj;
        final float strength = ditherStrength * 3.333f;
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y) & 0xF8F8F880;
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    int rr = ((color >>> 24)       );
                    int gg = ((color >>> 16) & 0xFF);
                    int bb = ((color >>> 8)  & 0xFF);
                    used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF];
                    pos = (px * 0.06711056f + y * 0.00583715f);
                    pos -= (int) pos;
                    pos *= 52.9829189f;
                    pos -= (int) pos;
                    adj = (pos * pos - 0.3f) * strength;
                    rr = MathUtils.clamp((int) (rr + (adj * (rr - (used >>> 24       )))), 0, 0xFF);
                    gg = MathUtils.clamp((int) (gg + (adj * (gg - (used >>> 16 & 0xFF)))), 0, 0xFF);
                    bb = MathUtils.clamp((int) (bb + (adj * (bb - (used >>> 8  & 0xFF)))), 0, 0xFF);
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
     * A blue-noise-based dither; does not diffuse error, and uses a tiling blue noise pattern (which can be accessed
     * with {@link #RAW_BLUE_NOISE}, but shouldn't usually be modified) as well as a fine-grained checkerboard pattern,
     * but only applies these noisy patterns when there's error matching a color from the image to a color in the
     * palette.
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceBlueNoise (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used, bn;
        float adj, strength = ditherStrength;
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y) & 0xF8F8F880;
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    color |= (color >>> 5 & 0x07070700) | 0xFE;
                    int rr = ((color >>> 24)       );
                    int gg = ((color >>> 16) & 0xFF);
                    int bb = ((color >>> 8)  & 0xFF);
                    used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF];
                    bn = PaletteReducer.RAW_BLUE_NOISE[(px & 63) | (y & 63) << 6];
                    adj = ((bn + 0.5f) * 0.007843138f);
                    adj *= adj * adj;
                    adj += ((px + y & 1) - 0.5f) * (127.5f - (2112 - bn * 13 & 255)) * 0x1.Cp-6f * strength;
                    rr = MathUtils.clamp((int) (rr + (adj * ((rr - (used >>> 24))))), 0, 0xFF);
                    gg = MathUtils.clamp((int) (gg + (adj * ((gg - (used >>> 16 & 0xFF))))), 0, 0xFF);
                    bb = MathUtils.clamp((int) (bb + (adj * ((bb - (used >>> 8 & 0xFF))))), 0, 0xFF);
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
     * This tends to be less "flat" than {@link #reduceBlueNoise(Pixmap)}, permitting more pixels to be different from
     * what {@link #reduceSolid(Pixmap)} would produce, but this generally looks good, especially with larger palettes.
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceChaoticNoise (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float adj, strength = ditherStrength;
        long s = 0xC13FA9A902A6328FL;
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y) & 0xF8F8F880;
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    color |= (color >>> 5 & 0x07070700) | 0xFE;
                    int rr = ((color >>> 24)       );
                    int gg = ((color >>> 16) & 0xFF);
                    int bb = ((color >>> 8)  & 0xFF);
                    used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF];
                    adj = ((PaletteReducer.RAW_BLUE_NOISE[(px & 63) | (y & 63) << 6] + 0.5f) * 0.007843138f);
                    adj *= adj * adj * strength;
                    s += color;
                    //// Complicated... This starts with a checkerboard of -0.5 and 0.5, times a tiny fraction.
                    //// The next 3 lines generate 3 low-quality-random numbers based on s, which should be
                    ////   different as long as the colors encountered so far were different. The numbers can
                    ////   each be positive or negative, and are reduced to a manageable size, summed, and
                    ////   multiplied by the earlier tiny fraction. Summing 3 random values gives us a curved
                    ////   distribution, centered on about 0.0 and weighted so most results are close to 0.
                    ////   Two of the random numbers use an XLCG, and the last uses an LCG. 
                    adj += ((px + y & 1) - 0.5f) * 0x1.2p-50f *
                            (((s ^ 0x9E3779B97F4A7C15L) * 0xC6BC279692B5CC83L >> 13) +
                                    ((~s ^ 0xDB4F0B9175AE2165L) * 0xD1B54A32D192ED03L >> 13) +
                                    ((s ^ color) * 0xD1342543DE82EF95L + 0x91E10DA5C79E7B1DL >> 13));
                    rr = MathUtils.clamp((int) (rr + (adj * ((rr - (used >>> 24))))), 0, 0xFF);
                    gg = MathUtils.clamp((int) (gg + (adj * ((gg - (used >>> 16 & 0xFF))))), 0, 0xFF);
                    bb = MathUtils.clamp((int) (bb + (adj * ((bb - (used >>> 8 & 0xFF))))), 0, 0xFF);
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
     * Given by Joel Yliluoma in <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">a dithering article</a>.
     */
    static final int[] thresholdMatrix = {
            0,  12,   3,  15,
            8,   4,  11,   7,
            2,  14,   1,  13,
            10,  6,   9,   5,
    };

    final int[] candidates = new int[16];

    /**
     * Compares items in ints by their luma, looking up items by the indices a and b, and swaps the two given indices if
     * the item at a has higher luma than the item at b. This is protected rather than private because it's more likely
     * that this would be desirable to override than a method that uses it, like {@link #reduceKnoll(Pixmap)}. Uses
     * {@link #LAB} to look up fairly-accurate luma for the given colors in {@code ints} (that contains RGBA8888 colors
     * while labs uses RGB555, so {@link #shrink(int)} is used to convert).
     * @param ints an int array than must be able to take a and b as indices; may be modified in place
     * @param a an index into ints
     * @param b an index into ints
     */
    protected void compareSwap(final int[] ints, final int a, final int b) {
        if(LAB[0][shrink(ints[a])] > LAB[0][shrink(ints[b])]) {
            final int t = ints[a];
            ints[a] = ints[b];
            ints[b] = t;
        }
    }

    /**
     * Sorting network, found by http://pages.ripco.net/~jgamble/nw.html , considered the best known for length 16.
     * @param i16 a 16-element array that will be sorted in-place by {@link #compareSwap(int[], int, int)}
     */
    void sort16(final int[] i16)
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
     * sorting step (this uses a sorting network, which works well for small input sizes like 16 items).
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
        final float errorMul = ditherStrength * 0.5f;
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    int er = 0, eg = 0, eb = 0;
                    cr = (color >>> 24);
                    cg = (color >>> 16 & 0xFF);
                    cb = (color >>> 8 & 0xFF);
                    for (int i = 0; i < candidates.length; i++) {
                        int rr = MathUtils.clamp((int) (cr + er * errorMul), 0, 255);
                        int gg = MathUtils.clamp((int) (cg + eg * errorMul), 0, 255);
                        int bb = MathUtils.clamp((int) (cb + eb * errorMul), 0, 255);
                        usedIndex = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))] & 0xFF;
                        candidates[i] = paletteArray[usedIndex];
                        used = gammaArray[usedIndex];
                        er += cr - (used >>> 24);
                        eg += cg - (used >>> 16 & 0xFF);
                        eb += cb - (used >>> 8 & 0xFF);
                    }
                    sort16(candidates);
                    pixmap.drawPixel(px, y, candidates[thresholdMatrix[((px & 3) | (y & 3) << 2)]]);
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
     * sorting step (this uses a sorting network, which works well for small input sizes like 16 items).
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
        int color, used, cr, cg, cb,  usedIndex;
        final float errorMul = ditherStrength * 0.3f;
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    int er = 0, eg = 0, eb = 0;
                    cr = (color >>> 24);
                    cg = (color >>> 16 & 0xFF);
                    cb = (color >>> 8 & 0xFF);
                    for (int c = 0; c < candidates.length; c++) {
                        int rr = MathUtils.clamp((int) (cr + er * errorMul), 0, 255);
                        int gg = MathUtils.clamp((int) (cg + eg * errorMul), 0, 255);
                        int bb = MathUtils.clamp((int) (cb + eb * errorMul), 0, 255);
                        usedIndex = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))] & 0xFF;
                        candidates[c] = paletteArray[usedIndex];
                        used = gammaArray[usedIndex];
                        er += cr - (used >>> 24);
                        eg += cg - (used >>> 16 & 0xFF);
                        eb += cb - (used >>> 8 & 0xFF);
                    }
                    sort16(candidates);
                    pixmap.drawPixel(px, y, candidates[thresholdMatrix[
                            ((int) (px * 0x0.C13FA9A902A6328Fp3 + y * 0x1.9E3779B97F4A7C15p2) & 3) ^
                                    ((px & 3) | (y & 3) << 2)
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
     * palette array with 256 or less elements that should have been used with {@link #exact(int[])} before to set the
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
    
}
