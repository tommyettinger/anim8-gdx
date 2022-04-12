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
        // there are two ways to do this; this is the older way, but it is deprecated in current libGDX: 
        pixmaps.add(ScreenUtils.getFrameBufferPixmap(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight()));
        // the newer way is only available in more-recent libGDX (I know 1.10.0 has it); it is not deprecated:
        // pixmaps.add(Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight()));
    }
    // AnimatedGif is from anim8; if no extra settings are specified it will calculate a 255-color palette from
    // each given frame and use the most appropriate palette for each frame, dithering any colors that don't
    // match. The other file-writing classes don't do this; PNG8 doesn't currently support a palette per-frame,
    // while AnimatedPNG doesn't restrict colors to a palette. See Dithering Algorithms below for visual things
    // to be aware of and choices you can make.
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
  api "com.github.tommyettinger:anim8-gdx:0.3.6"
}
```

You can also get a specific commit using JitPack, by following the instructions on
[JitPack's page for anim8](https://jitpack.io/#tommyettinger/anim8-gdx/da4f27d14b). (You usually want to select a recent
commit, unless you are experiencing problems with one in particular.)

A .gwt.xml file is present in the sources jar, and because GWT needs it, you can depend on the sources jar with
`implementation "com.github.tommyettinger:anim8-gdx:0.3.6:sources"`. The PNG-related code isn't available on GWT because
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
    - This changed slightly in 0.2.12, and should have less noticeable artifacts starting in that version.
    - A variant on Jorge Jimenez' Gradient Interleaved Noise.
  - PATTERN
    - A more traditional ordered dither that emphasizes accurately representing lightness changes.
    - Has a strong "quilt-like" square artifact that is more noticeable with small palette sizes.
    - Unusually slow to compute, but very accurate at preserving smooth shapes.
    - Very good at preserving shape, and the best at handling smooth gradients.
      - Changing the dither strength may have a small effect on lightness, but the effect
        to expect for PATTERN should be about the same as any other dither. This was different
        before version 0.2.8.
    - Uses Thomas Knoll's Pattern Dither, which is out-of-patent.
    - One of the best options when using large color palettes, and not very good for very small palettes.
  - DIFFUSION
    - This is Floyd-Steinberg error-diffusion dithering.
    - It tends to look very good in still images, and very bad in animations.
    - SCATTER is mostly the same as this algorithm, but uses blue noise to break up unpleasant patterns.
      - SCATTER is usually preferred. 
  - BLUE_NOISE
    - Blue noise, if you haven't heard the term, refers to a kind of sequence of values where low-frequency patterns
      don't appear at all, but mid- and high-frequency patterns are very common. 2D blue noise is common in graphics
      code, often as a texture but sometimes as a sequence of points; it is used here because most vertebrate eyes
      employ a blue-noise distribution for sensory cells, and this makes blue noise appear natural to the human eye.
    - Not the typical blue-noise dither; this incorporates a checkerboard pattern as well as a 64x64 blue noise texture.
    - I should probably credit Alan Wolfe for writing so many invaluable articles about blue noise,
      such as [this introduction](https://blog.demofox.org/2018/01/30/what-the-heck-is-blue-noise/).
      - This also uses a triangular-mapped blue noise texture, which means most of its pixels are in the middle of the
        range, and are only rarely very bright or dark. This helps the smoothness of the dithering.
      - Blue noise is also used normally by SCATTER and NEUE, as well as used strangely by CHAOTIC_NOISE.
    - This may have some issues when the palette is very small; it may not dither strongly enough by default for small
      palettes, which makes it look closer to NONE in those cases. It does fine with large palettes.
    - This changed in 0.2.12, and handles smooth gradients better now. In version 0.3.5, it changed again to improve
      behavior on small palettes.
  - CHAOTIC_NOISE
    - Like BLUE_NOISE, but it will dither different frames differently, and looks much more dirty/splattered.
    - This is an okay algorithm here for animations, but NEUE is much better, followed by SCATTER or PATTERN.
    - This may be somewhat more useful when using many colors than when using just a few.
    - It's rather ugly with small palettes, and really not much better on large palettes.
  - SCATTER
    - A hybrid of DIFFUSION and BLUE_NOISE, this avoids some regular artifacts in Floyd-Steinberg by adjusting diffused
      error with blue-noise values. 
    - This used to be the default and can still sometimes be the best here.
    - Unlike DIFFUSION, this is quite suitable for animations, but some fluid shapes look better with CHAOTIC_NOISE or
      GRADIENT_NOISE, and subtle gradients in still images are handled best by PATTERN and well by NEUE.
    - You may want to use a lower dither strength with SCATTER if you encounter horizontal line artifacts; 0.75 or 0.5
      should be low enough to eliminate them (not all palettes will experience these artifacts).
  - NEUE
    - Another hybrid of DIFFUSION and BLUE_NOISE, this has much better behavior on smooth gradients than SCATTER, at the
      price of not producing many flat areas of solid colors (it prefers to dither when possible).
    - This is the default and often the best of the bunch.
    - The code for NEUE is almost the same as for SCATTER, but where SCATTER *multiplies* the current error by a blue
      noise value (which can mean the blue noise could have no effect if error is 0), NEUE always *adds* in
      triangular-mapped blue noise to each pixel at the same amount.
    - SCATTER, as well as all other dither algorithms here except BLUE_NOISE and PATTERN, tend to have banding on smooth
      gradients, while NEUE doesn't usually have any banding.
      - Subtle banding sometimes happened even with NEUE on gradients before 0.3.5, but this improved in that release.
    - NEUE may sometimes look "sandy" when there isn't a single good matching color for a flat span of pixels; if this
      is a problem, SCATTER can look better.
    - NEUE is the most likely algorithm to change in new versions, unless another new algorithm is added.
  - Most algorithms have artifacts that stay the same across frames, which can be distracting for some palettes and some
    input images.
    - PATTERN has an obvious square grid.
    - BLUE_NOISE, SCATTER, ane NEUE have varying forms of a spongy blue noise texture.
    - GRADIENT_NOISE has a network of diagonal lines.
    - DIFFUSION tends to have its error corrections jump around between frames, which looks jarring.
    - CHAOTIC_NOISE has the opposite problem; it never keeps the same artifacts between frames, even if those frames are
      identical. This was also the behavior of NEUE in 0.3.0, but has since been changed.

You can set the strength of most of these dithers using PaletteReducer's, PNG8's, or AnimatedGif's
`setDitherStrength(float)` methods (use the method on the class that is producing output). For NONE,
there's no effect. For CHAOTIC_NOISE, there's almost no effect. For anything else, setting dither strength to close to 0
will approach the appearance of NONE, setting it close to 1.0 is the default, and strengths higher than 1 will make the
dither much stronger and may make the image less legible. NEUE, SCATTER, and DIFFUSION sometimes have trouble with very
high dither strengths, though how much trouble varies based on the palette, and they also tend to look good just before
major issues appear. NEUE is calibrated to look best at dither strength 1.0, but may stay looking good at higher
strengths for longer than SCATTER does. The `setDitherStrength(float)` methods on PNG8 and AnimatedGif were added in
version 0.3.5 .

# Palette Generation

You can create a PaletteReducer object by manually specifying an exact palette (useful for pixel art), attempting to
analyze an existing image or animation (which can work well for large palette sizes, but not small sizes), or using the
default palette (called "HALTONIC", it has 255 colors plus transparent). Of these, using `analyze()` is the trickiest,
and it generally should be permitted all 256 colors to work with. With `analyze()`, you can specify the threshold
between colors for it to consider adding one to the palette, and this is a challenging value to set that depends on the
image being dithered. Typically, between 50 and 600 are used, with higher values for smaller or more diverse palettes
(that is, ones with fewer similar colors to try to keep). Usually you will do just fine with the default "HALTONIC"
palette, or almost any practical 250+ color palette, because with so many colors it's hard to go wrong. Creating a
PaletteReducer without arguments, or calling `setDefaultPalette()` later, will set it to use HALTONIC.

As of version 0.3.3, GIF supports using a different palette for each frame of an
animation, analyzing colors separately for each frame. This supplements the previous behavior where a palette would
analyze all frames of an animation and find a 255-color palette that approximates the whole set of all frames
well-enough. PNG8 still uses the previous behavior, and you can use it with AnimatedGif by creating a PaletteReducer
with an `Array<Pixmap>` or calling `PaletteReducer.analyze(Array<Pixmap>)`. To analyze each frame separately, just make
sure the `palette` field of your `AnimatedGif` is null when you start writing a GIF. The `fastAnalysis` field on an
`AnimatedGif` object determines whether (if true) it uses a fast but approximate algorithm per frame, or (if false) it
uses the same analysis for each frame that it normally would for a still image. You can also create a `PaletteReducer`,
passing it an `Array<Pixmap>`, and assign that to the `palette` field; this is reasonably fast and also ensures every
frame will use the same palette (which means regions of solid color that don't change in the source won't change in the
GIF; this isn't true if `palette` is null).

# Samples

Some animations, using 255 colors taken from the most-used in the animation (`analyze()`, which does well here
because it can use all the colors), are [here on Imgur](https://imgur.com/a/R7rFpED). These are all indexed-color
animated PNG files, produced with the AnimatedGif class and converted to animated PNG with a separate tool; using this
approach seems to avoid lossy compression on Imgur. Those use AnimatedGif's new fastAnalysis option; you can compare
them with fastAnalysis set to false [here on Imgur](https://imgur.com/a/YDsAOVy). Running with fastAnalysis set to true
(and also generating APNG images on the side) took about 40 seconds; with fastAnalysis false, about 129 seconds.

Some more .gif animations were made with the new fastAnalysis option; you can compare with fastAnalysis set to true
[here on Imgur](https://imgur.com/a/nDwYNcP), and with fastAnalysis false [here on Imgur](https://imgur.com/a/TiyBZex).
Like before, these were all converted to APNG so Imgur won't compress them, but they kept the same palette(s). Running
with fastAnalysis set to true took about 25 seconds; with false, over 130 seconds.

Some .gif animations that reduce the colors of the "flashy" animation shown are [here on Imgur, reduced to black and
white](https://imgur.com/a/1bkxPFH), and [here on Imgur, reduced to 4-color "green-scale"](https://imgur.com/a/5G7amXn).

And some .png animations, using full color (made with the AnimatedPNG class):

![Flashy Full-Color PNG](https://i.imgur.com/36e4mXL.png)

![Pastel Full-Color PNG](https://i.imgur.com/22KiFSZ.png)

![Green Ogre Full-Color PNG](https://i.imgur.com/vjFiH5A.png)

A more intense usage is to encode a high-color video as an indexed-color GIF; why you might do this, I don't know,
but someone probably wants videos as GIFs. There's some test footage here from
["Video Of A Market" by Olivier Polome](https://www.pexels.com/video/video-of-a-market-4236787/), which is freely
licensed without requirements. You can run the test "VideoConvertDemo" to generate various GIFs locally. I can't
reasonably host the large GIF files with Git.

Animated PNG can support full alpha as well (though file sizes can be large):

![Full-Color PNG with Alpha](https://i.imgur.com/e1cRoSC.png)

Anim8 also can be used to support writing non-animated GIF images and indexed-mode PNG images.
Here's a retouched version of the Mona Lisa,
[source on Wikimedia Commons here](https://commons.wikimedia.org/wiki/File:Mona_Lisa_Digitally_Restored.tif), and
various 16-color dithers using a palette derived from the most frequent and different colors in the original:

Original (full-color):

![](https://i.imgur.com/sDJbRh2.png)

Neue (default):

![](https://i.imgur.com/jfAqJGk.png)

Pattern:

![](https://i.imgur.com/mgB8qIa.png)

Diffusion:

![](https://i.imgur.com/5WFV6fg.png)

Gradient Noise:

![](https://i.imgur.com/oDZlVkD.png)

Blue Noise:

![](https://i.imgur.com/HYy4776.png)

Chaotic Noise:

![](https://i.imgur.com/6QGFZzm.png)

Scatter:

![](https://i.imgur.com/17bDbzF.png)

None (no dither):

![](https://i.imgur.com/KisqTIh.png)

The analysis step that PaletteReducer performs prefers the most frequent colors in the image, and the Mona Lisa has
mostly dark gray, blue, and brown-to-flesh-tone colors. As such, the small amounts of green get forgotten when color
count is too low. This shows some green because the color count is 16 (not including transparent, which isn't present).
Lower color counts naturally have fewer colors.

(If the Wikimedia Commons source file is deleted, the original is available in the history of
[this other image](https://commons.wikimedia.org/wiki/File:Leonardo_da_Vinci_-_Mona_Lisa_(Louvre,_Paris)FXD.tif)).

# License

The code in this project is licensed under Apache 2.0 (see [LICENSE](LICENSE)). The test images have their own licenses,
though most are public-domain. Of the test images used in the src/test/resources/ folder and its subfolders...

  - Cat.jpg is a portrait of my cat, Satchmo; the image is public domain, the cat is not.
  - Frog.jpg is a public-domain image of a red-eyed tree frog, [taken by Carey James Balboa](https://commons.wikimedia.org/wiki/File:Red_eyed_tree_frog_edit2.jpg).
  - Landscape.jpg is [Among the Sierra Nevada by Albert Bierstadt](https://commons.wikimedia.org/wiki/File:Albert_Bierstadt_-_Among_the_Sierra_Nevada,_California_-_Google_Art_Project.jpg), a public domain oil painting.
  - Mona_Lisa.jpg is also a public domain oil painting, this one the Mona Lisa by Leonardo da Vinci, and [remastered by pixel8tor](https://commons.wikimedia.org/wiki/File:Mona_Lisa_Digitally_Restored.tif) to reduce the appearance of damage over time.
  - Pixel_Art.png is a snippet of a texture atlas made from some [wargame pixel art I previously released into the public domain](https://opengameart.org/content/pixvoxel-revised-isometric-wargame-sprites). 
  - Anemone.png is just a noise texture I generated with a tool I wrote; the image is public domain.
  - Earring.jpg is another public domain oil painting, "Girl with a Pearl Earring" by Johannes Vermeer, [accessed here](https://commons.wikimedia.org/wiki/File:1665_Girl_with_a_Pearl_EarringFXD.jpg).
  - The animation frames in the subfolders globe, oklab, solids, tank, tree, and tyrant all come from other projects of mine; all of these frames are public domain.
  - The animation frames in market are freely licensed without requirements, and are from ["Video Of A Market" by Olivier Polome](https://www.pexels.com/video/video-of-a-market-4236787/).
    - Pexels doesn't provide a standard open source license other than saying they are free to use without requirements.
