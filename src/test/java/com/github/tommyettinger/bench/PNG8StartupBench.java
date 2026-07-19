package com.github.tommyettinger.bench;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.TimeUtils;
import com.github.tommyettinger.anim8.Dithered;
import com.github.tommyettinger.anim8.PNG8;
import com.github.tommyettinger.anim8.PaletteReducer;

/*
 * Startup time:
 * With PAETH filter, compression 6 (default): between 2362 and 2467 ms in most cases.     File size: 13853 KB
 * With NONE filter, compression 2: between 1550 and 1610 ms in most cases.                File size:  9974 KB
 * This includes loading 90 Pixmaps from separate files, dithering them with SCATTER, and assembling an animated PNG8.
 */
/**
 * Timing:
 * <pre>
 * Took 4 ms to construct a PNG8
 * Took 176 ms to load the Array of Pixmap
 * Took 58 ms to configure
 * Took 2204 ms to write Scatter
 * Took 1774 ms to write Neue
 * Took 860 ms to write Gradient
 * Took 795 ms to write Roberts
 * Took 593 ms to write None
 * Took 8389 ms to write Pattern
 * Took 1272 ms to write Diffusion
 * Took 914 ms to write BlueNoise
 * Took 925 ms to write ChaoticNoise
 * Took 17967 ms total
 * </pre>
 * File sizes:
 * <pre>
 *  12MB PNG8-market-BlueNoise.png
 * 9.1MB PNG8-market-ChaoticNoise.png
 *  11MB PNG8-market-Diffusion.png
 * 9.3MB PNG8-market-Gradient.png
 *  13MB PNG8-market-Neue.png
 * 6.0MB PNG8-market-None.png
 * 9.7MB PNG8-market-Pattern.png
 * 9.2MB PNG8-market-Roberts.png
 *  13MB PNG8-market-Scatter.png
 * </pre>
 */
public class PNG8StartupBench extends ApplicationAdapter {
    private static final String name = "market";
    @Override
    public void create() {
        Gdx.files.local("tmp/imagesClean").mkdirs();
        Gdx.files.local("tmp/imagesClean").deleteDirectory();
        long startTime = TimeUtils.millis();
        PNG8 png8 = new PNG8();
        System.out.println("Took " + (TimeUtils.millis() - startTime) + " ms to construct a PNG8");
        long subTime = TimeUtils.millis();
        Array<Pixmap> pixmaps = new Array<>(true, 90, Pixmap[]::new);
        for (int i = 1; i <= 90; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".jpg")));
        }
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to load the Array of Pixmap");
        String namePalette;
        namePalette = name;
        subTime = TimeUtils.millis();
        png8.setPalette(new PaletteReducer());
        png8.setFlipY(false);
        png8.setCompression(2);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to configure");

        subTime = TimeUtils.millis();
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
        png8.write(Gdx.files.local("tmp/imagesClean/" + name + "/PNG8-" + namePalette + "-Scatter.png"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write Scatter");

        subTime = TimeUtils.millis();
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NEUE);
        png8.write(Gdx.files.local("tmp/imagesClean/" + name + "/PNG8-" + namePalette + "-Neue.png"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write Neue");

        subTime = TimeUtils.millis();
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
        png8.write(Gdx.files.local("tmp/imagesClean/" + name + "/PNG8-" + namePalette + "-Gradient.png"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write Gradient");

        subTime = TimeUtils.millis();
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.ROBERTS);
        png8.write(Gdx.files.local("tmp/imagesClean/" + name + "/PNG8-" + namePalette + "-Roberts.png"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write Roberts");

        subTime = TimeUtils.millis();
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
        png8.write(Gdx.files.local("tmp/imagesClean/" + name + "/PNG8-" + namePalette + "-None.png"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write None");

        subTime = TimeUtils.millis();
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
        png8.write(Gdx.files.local("tmp/imagesClean/" + name + "/PNG8-" + namePalette + "-Pattern.png"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write Pattern");

        subTime = TimeUtils.millis();
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
        png8.write(Gdx.files.local("tmp/imagesClean/" + name + "/PNG8-" + namePalette + "-Diffusion.png"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write Diffusion");

        subTime = TimeUtils.millis();
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
        png8.write(Gdx.files.local("tmp/imagesClean/" + name + "/PNG8-" + namePalette + "-BlueNoise.png"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - subTime) + " ms to write BlueNoise");

        subTime = TimeUtils.millis();
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
        png8.write(Gdx.files.local("tmp/imagesClean/" + name + "/PNG8-" + namePalette + "-ChaoticNoise.png"), pixmaps, 20);
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
		return new Lwjgl3Application(new PNG8StartupBench(), getDefaultConfiguration());
	}

	private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
		Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
		configuration.setWindowedMode(256, 256);
		return configuration;
	}

}
