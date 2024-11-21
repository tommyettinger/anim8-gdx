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

import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.utils.NumberUtils;

import java.util.Random;

/**
 * Various math functions that don't fit anywhere else, mostly relating to the shape of a distribution.
 * These include the parameterizable 0-1 curve produced by {@link #barronSpline(float, float, float)}, the
 * bell curve produced from a 0-1 input but with a larger output range by {@link #probit(double)}, and both
 * an accurate approximation of the cube root, {@link #cbrt(float)} and an inaccurate but similarly-shaped
 * method, {@link #cbrtShape(float)}.
 */
public final class OtherMath {
    private OtherMath(){}

    /**
     * Given a byte, pushes any value that isn't extreme toward the center of the 0 to 255 range, and keeps extreme
     * values (such as the channel values in the colors max green or black) as they are.
     * @param v a byte value that will be treated as if in the 0-255 range (as if unsigned)
     * @return a modified version of {@code v} that is more often near the center of the range than the extremes
     */
    public static byte centralize(byte v) {
        return (byte)(barronSpline((v & 255) * 0.003921569f, 0.5f, 0.5f) * 255.999f);
//        return (byte) (255.99 * (probit((v & 255) * 0x1p-8 + 0x1p-9) * 0.17327209222987916 + 0.5));
}

    /**
     * Approximates the natural logarithm of {@code x} (that is, with base E), using single-precision, somewhat roughly.
     * @param x the argument to the logarithm; must be greater than 0
     * @return an approximation of the logarithm of x with base E; can be any float
     */
    public static float logRough (float x)
    {
        final int vx = NumberUtils.floatToIntBits(x);
        final float mx = NumberUtils.intBitsToFloat((vx & 0x007FFFFF) | 0x3f000000);
        return vx * 8.262958E-8f - 86.10657f - 1.0383555f * mx - 1.1962888f / (0.3520887068f + mx);
    }

    // constants used by probitI() and probitF()
    private static final float
            a0f = 0.195740115269792f,
            a1f = -0.652871358365296f,
            a2f = 1.246899760652504f,
            b0f = 0.155331081623168f,
            b1f = -0.839293158122257f,
            c3f = -1.000182518730158122f,
            c0f = 16.682320830719986527f,
            c1f = 4.120411523939115059f,
            c2f = 0.029814187308200211f,
            d0f = 7.173787663925508066f,
            d1f = 8.759693508958633869f;


    // constants used by probitL() and probitD()
    private static final double
            a0 = 0.195740115269792,
            a1 = -0.652871358365296,
            a2 = 1.246899760652504,
            b0 = 0.155331081623168,
            b1 = -0.839293158122257,
            c3 = -1.000182518730158122,
            c0 = 16.682320830719986527,
            c1 = 4.120411523939115059,
            c2 = 0.029814187308200211,
            d0 = 7.173787663925508066,
            d1 = 8.759693508958633869;

    /**
     * A single-precision probit() approximation that takes a float between 0 and 1 inclusive and returns an
     * approximately-Gaussian-distributed float between -9.080134 and 9.080134 .
     * The function maps the lowest inputs to the most negative outputs, the highest inputs to the most
     * positive outputs, and inputs near 0.5 to outputs near 0.
     * <a href="https://www.researchgate.net/publication/46462650_A_New_Approximation_to_the_Normal_Distribution_Quantile_Function">Uses this algorithm by Paul Voutier</a>.
     * @param p should be between 0 and 1, inclusive.
     * @return an approximately-Gaussian-distributed float between -9.080134 and 9.080134
     */
    public static float probitF(float p) {
        if(0.0465f > p){
            float r = (float)Math.sqrt(logRough(1f/(p*p)));
            return c3f * r + c2f + (c1f * r + c0f) / (r * (r + d1f) + d0f);
        } else if(0.9535f < p) {
            float q = 1f - p, r = (float)Math.sqrt(logRough(1f/(q*q)));
            return -c3f * r - c2f - (c1f * r + c0f) / (r * (r + d1f) + d0f);
        } else {
            float q = p - 0.5f, r = q * q;
            return q * (a2f + (a1f * r + a0f) / (r * (r + b1f) + b0f));
        }
    }

    /**
     * A double-precision probit() approximation that takes a double between 0 and 1 inclusive and returns an
     * approximately-Gaussian-distributed double between -26.48372928592822 and 26.48372928592822 .
     * The function maps the lowest inputs to the most negative outputs, the highest inputs to the most
     * positive outputs, and inputs near 0.5 to outputs near 0.
     * <a href="https://www.researchgate.net/publication/46462650_A_New_Approximation_to_the_Normal_Distribution_Quantile_Function">Uses this algorithm by Paul Voutier</a>.
     * @param p should be between 0 and 1, inclusive.
     * @return an approximately-Gaussian-distributed double between -26.48372928592822 and 26.48372928592822
     */
    public static double probit(double p) {
        if(0.0465 > p){
            double q = p + 7.458340731200208E-155, r = Math.sqrt(Math.log(1.0/(q*q)));
            return c3 * r + c2 + (c1 * r + c0) / (r * (r + d1) + d0);
        } else if(0.9535 < p) {
            double q = 1.0 - p + 7.458340731200208E-155, r = Math.sqrt(Math.log(1.0/(q*q)));
            return -c3 * r - c2 - (c1 * r + c0) / (r * (r + d1) + d0);
        } else {
            double q = p - 0.5, r = q * q;
            return q * (a2 + (a1 * r + a0) / (r * (r + b1) + b0));
        }
    }

    /**
     * An approximation of the cube-root function for float inputs and outputs.
     * This can be about twice as fast as {@link Math#cbrt(double)}. It
     * correctly returns negative results when given negative inputs.
     * <br>
     * Has very low relative error (less than 1E-9) when inputs are uniformly
     * distributed between -512 and 512, and absolute mean error of less than
     * 1E-6 in the same scenario. Uses a bit-twiddling method similar to one
     * presented in Hacker's Delight and also used in early 3D graphics (see
     * <a href="https://en.wikipedia.org/wiki/Fast_inverse_square_root">Wikipedia: Fast inverse square root</a>
     * for more, but this code approximates cbrt(x) and not 1/sqrt(x)). This specific code
     * was originally by Marc B. Reynolds, posted in his
     * <a href="https://github.com/Marc-B-Reynolds/Stand-alone-junk/blob/master/src/Posts/ballcube.c#L182-L197">"Stand-alone-junk" repo</a> .
     * @param x any finite float to find the cube root of
     * @return the cube root of x, approximated
     */
    public static float cbrt(float x) {
        int ix = NumberUtils.floatToIntBits(x);
        final int sign = ix & 0x80000000;
        ix &= 0x7FFFFFFF;
        final float x0 = x;
        ix = (ix>>>2) + (ix>>>4);
        ix += (ix>>>4);
        ix = ix + (ix>>>8) + 0x2A5137A0 | sign;
        x  = NumberUtils.intBitsToFloat(ix);
        x  = 0.33333334f*(2f * x + x0/(x*x));
        x  = 0.33333334f*(2f * x + x0/(x*x));
        return x;
    }
    /**
     * An approximation of the cube-root function for float inputs and outputs.
     * This can be about twice as fast as {@link Math#cbrt(double)}. This
     * version does not tolerate negative inputs, because in the narrow use
     * case it has in this class, it is never given negative inputs.
     * <br>
     * Has very low relative error (less than 1E-9) when inputs are uniformly
     * distributed between 0 and 512, and absolute mean error of less than
     * 1E-6 in the same scenario. Uses a bit-twiddling method similar to one
     * presented in Hacker's Delight and also used in early 3D graphics (see
     * <a href="https://en.wikipedia.org/wiki/Fast_inverse_square_root">Wikipedia: Fast inverse square root</a>
     * for more, but this code approximates cbrt(x) and not 1/sqrt(x)). This specific code
     * was originally by Marc B. Reynolds, posted in his
     * <a href="https://github.com/Marc-B-Reynolds/Stand-alone-junk/blob/master/src/Posts/ballcube.c#L182-L197">"Stand-alone-junk" repo</a> .
     * It's worth noting that while hardware instructions for finding the
     * square root of a float have gotten extremely fast, the same is not
     * true for the cube root (which has to allow negative inputs), so while
     * the bit-twiddling inverse square root is no longer a beneficial
     * optimization on current hardware, this does seem to help.
     * <br>
     * This is used when converting from RGB to Oklab, as an intermediate step.
     * @param x any non-negative finite float to find the cube root of
     * @return the cube root of x, approximated
     */
    public static float cbrtPositive(float x) {
        int ix = NumberUtils.floatToIntBits(x);
        final float x0 = x;
        ix = (ix>>>2) + (ix>>>4);
        ix += (ix>>>4);
        ix += (ix>>>8) + 0x2A5137A0;
        x  = NumberUtils.intBitsToFloat(ix);
        x  = 0.33333334f*(2f * x + x0/(x*x));
        x  = 0.33333334f*(1.9999999f * x + x0/(x*x));
        return x;
    }

    /**
     * A function that loosely approximates the cube root of {@code x}, but is much smaller and probably faster than
     * {@link OtherMath#cbrt(float)}. This is meant to be used when you want the shape of a cbrt() function, but don't
     * actually care about it being the accurate mathematical cube-root.
     * <br>
     * This method is small enough that it make more sense to inline it than to call this exact implementation. The code
     * is simply, given a finite float x:
     * <pre>
     *     return x * 1.25f / (0.25f + Math.abs(x));
     * </pre>
     * 1.25f is the value M that this will gradually approach (but never reach) for positive inputs; negative inputs
     * approach -M instead. 0.25f is the value N that changes the curvature of the line this forms; using N closer to
     * 0.004f results in a shape closer to the actual cube root function, while using larger N (such as 1f or 2f) makes
     * the line closer to straight, with a shallow slope.
     * @param x any finite float
     * @return a loose approximation of the cube root of x; mostly useful for its shape
     */
    public static float cbrtShape(float x){
        /*
         * <a href="https://metamerist.blogspot.com/2007/09/faster-cube-root-iii.html">Initially given here</a> by
         * metamerist; I just made it respect sign.
         */
//        final int i = NumberUtils.floatToIntBits(x);
//        return NumberUtils.intBitsToFloat(((i & 0x7FFFFFFF) - 0x3F800000) / 3 + 0x3F800000 | (i & 0x80000000));
        return x * 1.25f / (0.25f + Math.abs(x));
    }

    /**
     * The quantile for the triangular distribution, this takes a float u in the range 0 to limit, both inclusive, and
     * produces a float between 0 and 1 that should be triangular-mapped if u was uniformly-distributed.
     * @param u should be between 0 (inclusive) and limit (inclusive)
     * @param limit the upper (inclusive) bound for u
     * @return a float between 0 and 1, both inclusive, that will be triangular-distributed if u was uniform
     */
    public static float triangularRemap(final float u, final float limit) {
        return (u <= 0.5f * limit
                ? (float)Math.sqrt((0.5f/limit) * u)
                : 1f - (float) Math.sqrt(0.5f - (0.5f/limit) * u));
    }

    /**
     * A variant on {@link Math#atan(double)} that does not tolerate infinite inputs and takes/returns floats.
     * @param i any finite float
     * @return an output from the inverse tangent function, from PI/-2.0 to PI/2.0 inclusive
     */
    private static float atanUnchecked(final float i) {
        final float n = Math.abs(i);
        final float c = (n - 1f) / (n + 1f);
        final float c2 = c * c;
        final float c3 = c * c2;
        final float c5 = c3 * c2;
        final float c7 = c5 * c2;
        return Math.signum(i) * (0.7853981633974483f +
                (0.999215f * c - 0.3211819f * c3 + 0.1462766f * c5 - 0.0389929f * c7));
    }

    /**
     * Close approximation of the frequently-used trigonometric method atan2, with higher precision than libGDX's atan2
     * approximation. Maximum error is below 0.00009 radians.
     * Takes y and x (in that unusual order) as floats, and returns the angle from the origin to that point in radians.
     * It is about 5 times faster than {@link Math#atan2(double, double)} (roughly 12 ns instead of roughly 62 ns for
     * Math, on Java 8 HotSpot). It is slightly faster than libGDX' MathUtils approximation of the same method;
     * MathUtils seems to have worse average error, though.
     * <br>
     * Credit for this goes to the 1955 research study "Approximations for Digital Computers," by RAND Corporation. This
     * is sheet 9's algorithm, which is the second-fastest and second-least precise. The algorithm on sheet 8 is faster,
     * but only by a very small degree, and is considerably less precise. That study provides an atan(float)
     * method, and the small code to make that work as atan2() was worked out from Wikipedia.
     * @param y y-component of the point to find the angle towards; note the parameter order is unusual by convention
     * @param x x-component of the point to find the angle towards; note the parameter order is unusual by convention
     * @return the angle to the given point, in radians as a float; ranges from -PI to PI
     */
    public static float atan2(final float y, float x) {
        float n = y / x;
        if(n != n) n = (y == x ? 1f : -1f); // if both y and x are infinite, n would be NaN
        else if(n - n != n - n) x = 0f; // if n is infinite, y is infinitely larger than x.
        if(x > 0)
            return atanUnchecked(n);
        else if(x < 0) {
            if(y >= 0)
                return atanUnchecked(n) + 3.14159265358979323846f;
            else
                return atanUnchecked(n) - 3.14159265358979323846f;
        }
        else if(y > 0) return x + 1.5707963267948966f;
        else if(y < 0) return x - 1.5707963267948966f;
        else return x + y; // returns 0 for 0,0 or NaN if either y or x is NaN
    }

    /**
     * A generalization on bias and gain functions that can represent both; this version is branch-less.
     * This is based on <a href="https://arxiv.org/abs/2010.09714">this micro-paper</a> by Jon Barron, which
     * generalizes the earlier bias and gain rational functions by Schlick. The second and final page of the
     * paper has useful graphs of what the s (shape) and t (turning point) parameters do; shape should be 0
     * or greater, while turning must be between 0 and 1, inclusive. This effectively combines two different
     * curving functions so they continue into each other when x equals turning. The shape parameter will
     * cause this to imitate "smoothstep-like" splines when greater than 1 (where the values ease into their
     * starting and ending levels), or to be the inverse when less than 1 (where values start like square
     * root does, taking off very quickly, but also end like square does, landing abruptly at the ending
     * level). You should only give x values between 0 and 1, inclusive.
     * @param x progress through the spline, from 0 to 1, inclusive
     * @param shape must be greater than or equal to 0; values greater than 1 are "normal interpolations"
     * @param turning a value between 0.0 and 1.0, inclusive, where the shape changes
     * @return a float between 0 and 1, inclusive
     */
    public static float barronSpline(final float x, final float shape, final float turning) {
        final float d = turning - x;
        final int f = com.badlogic.gdx.utils.NumberUtils.floatToIntBits(d) >> 31, n = f | 1;
        return ((turning * n - f) * (x + f)) / (Float.MIN_NORMAL - f + (x + shape * d) * n) - f;
    }

    /**
     * A wrapper around {@link #barronSpline(float, float, float)} to use it as an Interpolation.
     * Useful because it can imitate the wide variety of symmetrical Interpolations by setting turning to 0.5 and shape
     * to some value greater than 1, while also being able to produce the inverse of those interpolations by setting
     * shape to some value between 0 and 1.
     */
    public static class BiasGain extends Interpolation {
        /**
         * The shape parameter will cause this to imitate "smoothstep-like" splines when greater than 1 (where the
         * values ease into their starting and ending levels), or to be the inverse when less than 1 (where values
         * start like square root does, taking off very quickly, but also end like square does, landing abruptly at
         * the ending level).
         */
        public final float shape;
        /**
         * A value between 0.0 and 1.0, inclusive, where the shape changes.
         */
        public final float turning;

        /**
         * Constructs a useful default BiasGain interpolation with a smoothstep-like shape.
         * This has a shape of 2.0f and a turning of 0.5f .
         */
        public BiasGain() {
            this(2f, 0.5f);
        }

        /**
         * Constructs a BiasGain interpolation with the specified (positive) shape and specified turning (between 0 and
         * 1 inclusive).
         * @param shape must be positive; similar to a straight line when near 1, becomes smoothstep-like above 1, and
         *              becomes shaped like transpose of smoothstep below 1
         * @param turning where, between 0 and 1 inclusive, this should change from the starting curve to the ending one
         */
        public BiasGain (float shape, float turning) {
            this.shape = shape;
            this.turning = turning;
        }

        public float apply (float a) {
            return barronSpline(a, shape, turning);
        }
    }
}
