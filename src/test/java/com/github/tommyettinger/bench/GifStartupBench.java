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
 * Timing:
 * <pre>
 * Took 1 ms to construct an AnimatedGif
 * Took 179 ms to load the Array of Pixmap
 * Took 52 ms to configure
 * Took 2021 ms to write Scatter
 * Took 1700 ms to write Neue
 * Took 1045 ms to write Gradient
 * Took 969 ms to write Roberts
 * Took 748 ms to write None
 * Took 7851 ms to write Pattern
 * Took 1526 ms to write Diffusion
 * Took 1130 ms to write BlueNoise
 * Took 1105 ms to write ChaoticNoise
 * Took 18327 ms total
 * </pre>
 * File sizes:
 * <pre>
 *  13MB AnimatedGif-market-BlueNoise.gif
 * 9.7MB AnimatedGif-market-ChaoticNoise.gif
 *  13MB AnimatedGif-market-Diffusion.gif
 *  11MB AnimatedGif-market-Gradient.gif
 *  16MB AnimatedGif-market-Neue.gif
 * 7.0MB AnimatedGif-market-None.gif
 *  13MB AnimatedGif-market-Pattern.gif
 *  11MB AnimatedGif-market-Roberts.gif
 *  15MB AnimatedGif-market-Scatter.gif
 * </pre>
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
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to load the Array of Pixmap");
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
