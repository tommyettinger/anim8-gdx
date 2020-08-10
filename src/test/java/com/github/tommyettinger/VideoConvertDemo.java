package com.github.tommyettinger;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.TimeUtils;
import com.github.tommyettinger.anim8.AnimatedGif;
import com.github.tommyettinger.anim8.AnimatedPNG;
import com.github.tommyettinger.anim8.Dithered;
import com.github.tommyettinger.anim8.PNG8;

/**
 * This is from the NorthernLights demo in SquidLib-Demos, available
 * <a href="https://github.com/tommyettinger/SquidLib-Demos/tree/master/NorthernLights">here</a>.
 * <p>
 * Credit for the shader adaptation goes to angelickite , a very helpful user on the libGDX Discord.
 * The Discord can be found at <a href="https://discord.gg/crTrDEK">this link</a>.
 */
public class VideoConvertDemo extends ApplicationAdapter {
    private long startTime;
    private int width, height;
    private static final String name = "market";
    @Override
    public void create() {
        startTime = TimeUtils.millis();
        width = Gdx.graphics.getWidth();
        height = Gdx.graphics.getHeight();

        Gdx.files.local("images").mkdirs();
//		renderAPNG(); // comment this out if you aren't using the full-color animated PNGs, because this is slow.
//		renderPNG8();
        renderGif();
        Gdx.app.exit();
    }

    @Override
    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public void render() { 
        
    }

    public void renderAPNG() {
        Array<Pixmap> pixmaps = new Array<>(90);
        for (int i = 1; i <= 90; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".jpg")));
        }
        AnimatedPNG apng = new AnimatedPNG();
        apng.setCompression(7);
        apng.write(Gdx.files.local("images/AnimatedPNG-" + name + ".png"), pixmaps, 20);
    }
    
    public void renderPNG8() {
        Array<Pixmap> pixmaps = new Array<>(90);
        for (int i = 1; i <= 90; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".jpg")));
        }
        PNG8 png8 = new PNG8();
        png8.setFlipY(false);
        png8.setCompression(7);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
        png8.write(Gdx.files.local("images/PNG8-" + name + "-None.png"), pixmaps, 20);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
        png8.write(Gdx.files.local("images/PNG8-" + name + "-Diffusion.png"), pixmaps, 20);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
        png8.write(Gdx.files.local("images/PNG8-" + name + "-Pattern.png"), pixmaps, 20);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
        png8.write(Gdx.files.local("images/PNG8-" + name + "-GradientNoise.png"), pixmaps, 20);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
        png8.write(Gdx.files.local("images/PNG8-" + name + "-BlueNoise.png"), pixmaps, 20);
    }

    public void renderGif() {
        Array<Pixmap> pixmaps = new Array<>(90);
        for (int i = 1; i <= 90; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".jpg")));
        }
        AnimatedGif gif = new AnimatedGif();
        gif.setFlipY(false);
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
        gif.write(Gdx.files.local("images/AnimatedGif-" + name + "-None.gif"), pixmaps, 20);
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
        gif.write(Gdx.files.local("images/AnimatedGif-" + name + "-Diffusion.gif"), pixmaps, 20);
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
        gif.write(Gdx.files.local("images/AnimatedGif-" + name + "-Pattern.gif"), pixmaps, 20);
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
        gif.write(Gdx.files.local("images/AnimatedGif-" + name + "-GradientNoise.gif"), pixmaps, 20);
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
        gif.write(Gdx.files.local("images/AnimatedGif-" + name + "-BlueNoise.gif"), pixmaps, 20);
    }

	public static void main(String[] args) {
		createApplication();
	}

	private static Lwjgl3Application createApplication() {
		return new Lwjgl3Application(new VideoConvertDemo(), getDefaultConfiguration());
	}

	private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
		Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
		configuration.setTitle("Anim8-GDX Video Convert Writer");
		configuration.setWindowedMode(256, 256);
		configuration.useVsync(true);
		configuration.setIdleFPS(20);
		return configuration;
	}

}
