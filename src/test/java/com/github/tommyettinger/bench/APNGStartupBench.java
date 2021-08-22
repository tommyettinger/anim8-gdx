package com.github.tommyettinger.bench;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.TimeUtils;
import com.github.tommyettinger.anim8.AnimatedPNG;

/**
 * Startup time: between 4071 and 4090 ms in most cases, once 4999 ms.     File size: 21777 KB
 * This includes loading 90 Pixmaps from separate files and assembling an animated PNG (full-color).
 * <br>
 * Without Paeth, but with the same Buffer-less way as PNG8:
 * Startup time: between 2325 and 2355 ms in most cases.                   File size: 34291 KB
 * <br>
 * With Paeth, and again with the same Buffer-less way as PNG:
 * Startup time: between 4039 and 4121 ms in most cases.                   File size: 21777 KB
 * <br>
 * With Sub filter, same Buffer-less way:
 * Startup time: between 3030 and 3101 ms in most cases.                   File size: 22393 KB
 * <br
 * This is an unusually large animated PNG, and a lot of usage won't need a 20+ MB APNG file. However, we save about a
 * second per such file by switching from the Paeth filter to the Sub filter, and only have slightly worse file size
 * (less than 3% larger).
 */
public class APNGStartupBench extends ApplicationAdapter {
    private static final String name = "market";
    @Override
    public void create() {

        Gdx.files.local("tmp/imagesSub").mkdirs();
        long startTime = TimeUtils.millis();
        AnimatedPNG apng = new AnimatedPNG();
        System.out.println("Took " + (TimeUtils.millis() - startTime) + " ms to construct an AnimatedPNG");
        Array<Pixmap> pixmaps = new Array<>(true, 90, Pixmap.class);
        for (int i = 1; i <= 90; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".jpg")));
        }
        String namePalette;
        namePalette = name;
        apng.setFlipY(false);
        apng.write(Gdx.files.local("tmp/imagesSub/" + name + "/APNG-" + namePalette + ".png"), pixmaps, 20);
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
		return new Lwjgl3Application(new APNGStartupBench(), getDefaultConfiguration());
	}

	private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
		Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
		configuration.setWindowedMode(256, 256);
		return configuration;
	}

}
