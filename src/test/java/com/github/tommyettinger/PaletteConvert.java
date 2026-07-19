package com.github.tommyettinger;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import com.github.tommyettinger.anim8.*;

import static com.github.tommyettinger.Config.ALGORITHMS;

/**
 * Used to try converting logos to different palettes. Loads {@code logos/logo.png}, which must be supplied, and tries
 * to convert it to use only colors in {@code logos/target_palette.png} .
 */
public class PaletteConvert extends ApplicationAdapter {
    @Override
    public void create() {
        //Gdx.app.setLogLevel(Application.LOG_DEBUG);
        Gdx.files.local("images").mkdirs();
		PNG8 png8 = new PNG8();
		png8.setFlipY(false);
		png8.setCompression(2);
		FileHandle file = Gdx.files.classpath("logos/logo.png");
		String name = file.nameWithoutExtension();
		Pixmap pixmap = new Pixmap(file);
		QualityPalette quality = new QualityPalette(new Pixmap(Gdx.files.classpath("logos/target_palette.png")));
		png8.setPalette(quality);
		for (int i = 1; i < 8; i++) {
			png8.setDitherStrength(1f / i);
			for (Dithered.DitherAlgorithm d : ALGORITHMS) {
				png8.setDitherAlgorithm(d);
				png8.write(Gdx.files.local("images/png/" + name + "/" + name + "-PNG8-" + d + "-" + i +  ".png"), pixmap, false, true);
			}
		}
		Gdx.app.exit();
    }
    
    @Override
    public void render() {
        ScreenUtils.clear(0f, 0f, 0f, 0f);
    }

	public static void main(String[] args) {
		new Lwjgl3Application(new PaletteConvert(), getDefaultConfiguration());
	}

	private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
		Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
		configuration.setTitle("Anim8-GDX Palette Convert Demo");
		configuration.setWindowedMode(256, 256);
		configuration.useVsync(true);
		configuration.setIdleFPS(20);
		return configuration;
	}

}
