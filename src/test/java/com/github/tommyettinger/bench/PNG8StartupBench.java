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

/**
 * Startup time: between 2362 and 2467 ms in most cases.
 * This includes loading 90 Pixmaps from separate files, dithering them with SCATTER, and assembling an animated PNG8.
 */
public class PNG8StartupBench extends ApplicationAdapter {
    private static final String name = "market";
    @Override
    public void create() {

        Gdx.files.local("tmp/images").mkdirs();
        long startTime = TimeUtils.millis();
        Array<Pixmap> pixmaps = new Array<>(true, 90, Pixmap.class);
        for (int i = 1; i <= 90; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".jpg")));
        }
        PNG8 png8 = new PNG8();
        String namePalette;
        namePalette = name;
        png8.setPalette(new PaletteReducer());
        png8.setFlipY(false);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
        png8.write(Gdx.files.local("tmp/images/" + name + "/PNG8-" + namePalette + "-Scatter.png"), pixmaps, 20);
        System.out.println("Took " + (TimeUtils.millis() - startTime) + " ms");
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
