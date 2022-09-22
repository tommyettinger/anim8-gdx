package com.github.tommyettinger.bench;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.TimeUtils;
import com.github.tommyettinger.anim8.AnimatedGif;
import com.github.tommyettinger.anim8.Dithered;
import com.github.tommyettinger.anim8.AnimatedGif;
import com.github.tommyettinger.anim8.PaletteReducer;

/**
 * Startup time: between 1622 and 1660 ms in most cases.     File size: 10992 KB
 * This includes loading 90 Pixmaps from separate files, dithering them with SCATTER, and assembling an animated GIF.
 */
public class GifStartupBench extends ApplicationAdapter {
    private static final String name = "market";
    @Override
    public void create() {
        Gdx.files.local("tmp/imagesClean").mkdirs();
        Gdx.files.local("tmp/imagesClean").deleteDirectory();
        long startTime = TimeUtils.millis();
        AnimatedGif gif = new AnimatedGif();
        System.out.println("Took " + (TimeUtils.millis() - startTime) + " ms to construct an AnimatedGif");
        long subTime = TimeUtils.millis();
        Array<Pixmap> pixmaps = new Array<>(true, 90, Pixmap.class);
        for (int i = 1; i <= 90; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".jpg")));
        }
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to load the Array<Pixmap>");
        String namePalette;
        namePalette = name;
        subTime = TimeUtils.millis();
        gif.setPalette(new PaletteReducer());
        gif.setFlipY(false);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to configure");

        subTime = TimeUtils.millis();
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
        gif.write(Gdx.files.local("tmp/imagesClean/" + name + "/AnimatedGif-" + namePalette + "-Scatter.gif"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write Scatter");

        subTime = TimeUtils.millis();
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NEUE);
        gif.write(Gdx.files.local("tmp/imagesClean/" + name + "/AnimatedGif-" + namePalette + "-Neue.gif"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write Neue");

        subTime = TimeUtils.millis();
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
        gif.write(Gdx.files.local("tmp/imagesClean/" + name + "/AnimatedGif-" + namePalette + "-Gradient.gif"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write Gradient");

        subTime = TimeUtils.millis();
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.ROBERTS);
        gif.write(Gdx.files.local("tmp/imagesClean/" + name + "/AnimatedGif-" + namePalette + "-Roberts.gif"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write Roberts");

        subTime = TimeUtils.millis();
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
        gif.write(Gdx.files.local("tmp/imagesClean/" + name + "/AnimatedGif-" + namePalette + "-None.gif"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write None");

        subTime = TimeUtils.millis();
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
        gif.write(Gdx.files.local("tmp/imagesClean/" + name + "/AnimatedGif-" + namePalette + "-Pattern.gif"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write Pattern");

        subTime = TimeUtils.millis();
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
        gif.write(Gdx.files.local("tmp/imagesClean/" + name + "/AnimatedGif-" + namePalette + "-Diffusion.gif"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write Diffusion");

        subTime = TimeUtils.millis();
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
        gif.write(Gdx.files.local("tmp/imagesClean/" + name + "/AnimatedGif-" + namePalette + "-BlueNoise.gif"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write BlueNoise");

        subTime = TimeUtils.millis();
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
        gif.write(Gdx.files.local("tmp/imagesClean/" + name + "/AnimatedGif-" + namePalette + "-ChaoticNoise.gif"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write ChaoticNoise");

        System.out.println("Took " + (TimeUtils.millis() - startTime) + " ms total");
        Gdx.app.exit();
    }

    @Override
    public void resize(int width, int height) {
    }

    @Override
    public void render() { 
        
    }

	public static void main(String[] args) {
		createApplication();
	}

	private static Lwjgl3Application createApplication() {
		return new Lwjgl3Application(new GifStartupBench(), getDefaultConfiguration());
	}

	private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
		Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
		configuration.setWindowedMode(256, 256);
		return configuration;
	}

}
