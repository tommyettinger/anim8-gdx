# anim8-gdx
Support for writing animated GIF, PNG8, and animated PNG (including full-color) from libGDX

There's been support for writing some image-file types from libGDX for a while, via its PixmapIO class.
PixmapIO can write full-color PNG files, plus the libGDX-specific CIM file format. It can't write any
animated image formats, nor can it write any indexed-mode images (which use a palette, and tend to be
smaller files). This library, anim8, allows libGDX applications to write animated GIF files, indexed-mode
PNG files, and animated PNG files (with either full-color or palette-based color). The API tries to
imitate the PixmapIO.PNG nested class, but supporting a palette needs some new methods. For a simple use
case, here's a `writeGif()` method that calls `render()` 20 times and screenshots each frame:

```java
public void writeGif() {
    final int frameCount = 20;
    Array<Pixmap> pixmaps = new Array<>(frameCount);
    for (int i = 0; i < frameCount; i++) {
        // you could set the proper state for a frame here.

        // you don't need to call render() in all cases, especially if you have Pixmaps already.
        // this assumes you're calling this from a class that uses render() to draw to the screen.
        render();
        // this gets a screenshot of the current window and adds it to the Array of Pixmap.
        pixmaps.add(ScreenUtils.getFrameBufferPixmap(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight()));
    }
    // AnimatedGif is from anim8; if no extra settings are specified it will calculate a 255-color palette from
    // the given pixmaps and use that for all frames, dithering any colors that don't match.
    // see Dithering Algorithms below for visual things to be aware of and choices you can take.
    AnimatedGif gif = new AnimatedGif();
    // you can write to a FileHandle or an OutputStream; here, the file will be written in the current directory.
    // here, pixmaps is usually an Array of Pixmap for any of the animated image types.
    // 16 is how many frames per second the animated GIF should play back at.
    gif.write(Gdx.files.local("AnimatedGif.gif"), pixmaps, 16);
}
```

The above code uses AnimatedGif, but could also use AnimatedPNG or PNG8 to write to an animated PNG (with full-color or
palette-based color, respectively).

# Install

A typical Gradle dependency on anim8 looks like this (in the core module's dependencies for a typical libGDX project):
```groovy
dependencies {
  //... other dependencies are here, like libGDX 1.9.11 or higher
  // libGDX 1.10.0 is recommended currently, but versions as old as 1.9.11 work.
  api "com.github.tommyettinger:anim8-gdx:0.2.10"
}
```

You can also get a specific commit using JitPack, by following the instructions on
[JitPack's page for anim8](https://jitpack.io/#tommyettinger/anim8-gdx/633d5e0bdc). 

A .gwt.xml file is present in the sources jar, and because GWT needs it, you can depend on the sources jar with
`implementation "com.github.tommyettinger:anim8-gdx:0.2.10:sources"`. The PNG-related code isn't available on GWT because
it needs `java.util.zip`, which is unavailable there, but PaletteReducer and AnimatedGif should both work. The GWT
inherits line, which is needed in `GdxDefinition.gwt.xml` if no dependencies already have it, is:
```xml
``<inherits name="anim8" />
```

# Dithering Algorithms
You have a choice between several dithering algorithms if you write to GIF or PNG8; you can also avoid choosing one
entirely by using AnimatedPNG (it can use full color) or libGDX's PixmapIO.PNG (which isn't animated and has a slightly
different API).

  - NONE
    - No dither. Solid blocks of color only. Often looks bad unless the original image had few colors.
  - GRADIENT_NOISE
    - A solid choice of an ordered dither, though it may have visible artifacts in the form of zig-zag diagonal lines.
    - A variant on Jorge Jimenez' Gradient Interleaved Noise.
  - PATTERN
    - A more traditional ordered dither that's been skewed, so it doesn't have square artifacts.
    - Unusually slow to compute, but very accurate at preserving smooth shapes.
    - Very good at preserving shape, and the best at handling smooth gradients.
      - Changing the dither strength may have a small effect on lightness, but the effect
        to expect for PATTERN should be about the same as any other dither. This was different
        before version 0.2.8.
    - A variant on Thomas Knoll's Pattern Dither, which is out-of-patent.
    - One of the best options when using large color palettes, and not very good for very small palettes.
  - DIFFUSION
    - This is Floyd-Steinberg error-diffusion dithering.
    - It tends to look very good in still images, and very bad in animations.
    - SCATTER is mostly the same as this algorithm, but uses blue noise to break up unpleasant patterns.
  - BLUE_NOISE
    - Blue noise, if you haven't heard the term, refers to a kind of sequence of values where low-frequency patterns
      don't appear at all, but mid- and high-frequency patterns are very common. 2D blue noise is common in graphics
      code, often as a texture but sometimes as a sequence of points; it is used here because most vertebrate eyes
      employ a blue-noise distribution for sensory cells, and this makes blue noise appear natural to the human eye.
    - Not the typical blue-noise dither; this incorporates a checkerboard pattern as well as a 64x64 blue noise texture.
    - I should probably credit Alan Wolfe for writing so many invaluable articles about blue noise,
      such as [this introduction](https://blog.demofox.org/2018/01/30/what-the-heck-is-blue-noise/).
    - This may have some issues when the palette is very small; it may not dither strongly enough by default for small
      palettes, which makes it look closer to NONE in those cases. It does fine with large palettes.
  - CHAOTIC_NOISE
    - Like BLUE_NOISE, but it will dither different frames differently, and can look somewhat more chaotic.
    - This is probably the third-best algorithm here for animations, after SCATTER and PATTERN in either order.
    - This may be more useful when using many colors than when using just a few.
  - SCATTER
    - A hybrid of DIFFUSION and BLUE_NOISE, this avoids some regular artifacts in Floyd-Steinberg by adjusting diffused
      error with blue-noise values. 
    - This is the default and often the best of the bunch.
    - Unlike DIFFUSION, this is quite suitable for animations, but some fluid shapes look better with CHAOTIC_NOISE or
      GRADIENT_NOISE, and subtle gradients in still images are handled best by PATTERN.
    - You may want to use a lower dither strength with SCATTER if you encounter horizontal line artifacts; 0.75 or 0.5
      should be low enough to eliminate them (not all palettes will experience these artifacts). 

You can set the strength of some of these dithers using PaletteReducer's `setDitherStrength(float)` method. For NONE,
there's no effect. For CHAOTIC_NOISE, there's almost no effect. For anything else, setting dither strength to close to 0
will approach the appearance of NONE, while setting it close to 1.0 (or higher) will make the dither much stronger and
may make the image less legible. SCATTER and also DIFFUSION have trouble with very high dither strengths, though how
much trouble varies based on the palette, and they also tend to look good just before major issues appear.

# Palette Generation

You can create a PaletteReducer object by manually specifying an exact palette (useful for pixel art), attempting to
analyze an existing image or animation (which can work well for large palette sizes, but not small sizes), or using the
default palette (called "HALTONIC", it has 255 colors plus transparent). Of these, using `analyze()` is the trickiest,
and it generally should be permitted all 256 colors to work with. With `analyze()`, you can specify the threshold
between colors for it to consider adding one to the palette, and this is a challenging value to set that depends on the
image being dithered. Typically, between 150 and 600 are used, with higher values for smaller or more diverse palettes
(that is, ones with fewer similar colors to try to keep). Usually you will do just fine with the default "HALTONIC"
palette, or almost any practical 250+ color palette, because with so many colors it's hard to go wrong. Creating a
PaletteReducer without arguments, or calling `setDefaultPalette()` later, will set it to use HALTONIC.

# Samples
Some .gif animations, using 255 colors taken from the most-used in the animation (`analyze()`, which does well here
because it can use all the colors):

Pattern dither:

![Flashy Gif, pattern dither](images/AnimatedGif-flashy-pattern.gif)

Gradient dither:

![Flashy Gif, gradient dither](images/AnimatedGif-flashy-gradient.gif)

Diffusion dither (Floyd-Steinberg):

![Flashy Gif, diffusion dither](images/AnimatedGif-flashy-diffusion.gif)

Blue Noise dither:

![Flashy Gif, blue noise dither](images/AnimatedGif-flashy-blueNoise.gif)

Chaotic Noise dither:

![Flashy Gif, chaotic noise dither](images/AnimatedGif-flashy-chaoticNoise.gif)

Scatter dither:

![Flashy Gif, scatter dither](images/AnimatedGif-flashy-scatter.gif)

No dither:

![Flashy Gif, no dither](images/AnimatedGif-flashy-none.gif)

Pattern dither:

![Pastel Gif, pattern dither](images/AnimatedGif-pastel-pattern.gif)

Gradient dither:

![Pastel Gif, gradient dither](images/AnimatedGif-pastel-gradient.gif)

Diffusion dither (Floyd-Steinberg):

![Pastel Gif, diffusion dither](images/AnimatedGif-pastel-diffusion.gif)

Blue Noise dither:

![Pastel Gif, blue noise dither](images/AnimatedGif-pastel-blueNoise.gif)

Chaotic Noise dither:

![Pastel Gif, chaotic noise dither](images/AnimatedGif-pastel-chaoticNoise.gif)

Scatter dither:

![Pastel Gif, scatter dither](images/AnimatedGif-pastel-scatter.gif)

No dither:

![Pastel Gif, no dither](images/AnimatedGif-pastel-none.gif)

Some .gif animations that reduce the colors of the first two animations shown:

Black and white pattern dither:

![BW Gif, pattern dither](images/AnimatedGif-bw-pattern.gif)

Black and white gradient dither:

![BW Gif, gradient dither](images/AnimatedGif-bw-gradient.gif)

Black and white diffusion dither (Floyd-Steinberg):

![BW Gif, diffusion dither](images/AnimatedGif-bw-diffusion.gif)

Black and white blue noise dither:

![BW Gif, blue noise dither](images/AnimatedGif-bw-blueNoise.gif)

Black and white scatter dither:

![BW Gif, scatter dither](images/AnimatedGif-bw-scatter.gif)

Black and white no dither:

![BW Gif, no dither](images/AnimatedGif-bw-none.gif)

4-color green-scale pattern dither:

![GB Gif, pattern dither](images/AnimatedGif-gb-pattern.gif)

4-color green-scale gradient dither:

![GB Gif, gradient dither](images/AnimatedGif-gb-gradient.gif)

4-color green-scale diffusion dither (Floyd-Steinberg):

![GB Gif, diffusion dither](images/AnimatedGif-gb-diffusion.gif)

4-color green-scale blue noise dither:

![GB Gif, blue noise dither](images/AnimatedGif-gb-blueNoise.gif)

4-color green-scale scatter dither:

![GB Gif, scatter dither](images/AnimatedGif-gb-scatter.gif)

And some .png animations, using full color:

![Flashy Full-Color PNG](images/AnimatedPNG-flashy.png)

![Pastel Full-Color PNG](images/AnimatedPNG-pastel.png)

![Green Full-Color PNG](images/AnimatedPNG-green.png)

A more intense usage is to encode a high-color video as an indexed-color GIF; why you might do this, I don't know,
but someone probably wants videos as GIFs. The images here are 90 frames from
["Video Of A Market" by Olivier Polome](https://www.pexels.com/video/video-of-a-market-4236787/), which is freely
licensed without requirements. Note that the following animations are limited to 255 colors, and the mp4 video
they use as a source has some block artifacts. [You can see the effects of different dither algorithms on the same video in this folder](https://github.com/tommyettinger/anim8-gdx/tree/master/images/market).

Animated PNG can support full alpha as well (though file sizes can be large):

![Full-Color PNG with Alpha](images/AnimatedPNG-alpha.png)

Anim8 also can be used to support writing non-animated GIF images and indexed-mode PNG images.
Here's a retouched version of the Mona Lisa,
[source on Wikimedia Commons here](https://commons.wikimedia.org/wiki/File:Mona_Lisa_Digitally_Restored.tif), and
various 15-color dithers using a palette derived from the most frequent and different colors in the original:

Original (full-color):

![](images/Mona_Lisa-PNG-Full.png)

Pattern:

![](images/Mona_Lisa-Gif-Pattern-16.gif)

Diffusion:

![](images/Mona_Lisa-Gif-Diffusion-16.gif)

Gradient Noise:

![](images/Mona_Lisa-Gif-Gradient-16.gif)

Blue Noise:

![](images/Mona_Lisa-Gif-BlueNoise-16.gif)

Chaotic Noise:

![](images/Mona_Lisa-Gif-ChaoticNoise-16.gif)

Scatter:

![](images/Mona_Lisa-Gif-Scatter-16.gif)

None (no dither):

![](images/Mona_Lisa-Gif-None-16.gif)

The analysis step that PaletteReducer performs prefers the most frequent colors in the image, and the Mona Lisa has
mostly dark gray, blue, and brown-to-flesh-tone colors. As such, the small amounts of green get forgotten when color
count is low.

(If the Wikimedia Commons source file is deleted, the original is available in the history of
[this other image](https://commons.wikimedia.org/wiki/File:Leonardo_da_Vinci_-_Mona_Lisa_(Louvre,_Paris)FXD.tif)).

There are other generated images, both .gif and .png, with different color counts and algorithms used in the images/
folder. AllRGB.png uses all possible RGB colors exactly once; it was made by user Meyermagic and called Hamiltonian 1,
[available here](https://allrgb.com/hamiltonian-1). Cat.jpg is my cat, Satchmo. Frog.jpg is a public-domain image of a
red-eyed tree frog, [taken by Carey James Balboa](https://commons.wikimedia.org/wiki/File:Red_eyed_tree_frog_edit2.jpg).
Landscape.jpg is [Among the Sierra Nevada by Albert Bierstadt](https://commons.wikimedia.org/wiki/File:Albert_Bierstadt_-_Among_the_Sierra_Nevada,_California_-_Google_Art_Project.jpg),
a public domain oil painting. Mona_Lisa.jpg is also a public domain oil painting, this one the Mona Lisa by Leonardo da
Vinci, and [remastered by pixel8tor](https://commons.wikimedia.org/wiki/File:Mona_Lisa_Digitally_Restored.tif) to reduce
the appearance of damage over time. Pixel_Art.png is a snippet of a texture atlas made from some [wargame pixel art I
previously released into the public domain](https://opengameart.org/content/pixvoxel-revised-isometric-wargame-sprites).
The only image I am not certain on the licensing of is Hamiltonian 1 (AllRGB.png); if there are any issues with it, I
will remove it and ideally replace it with another image with all colors.