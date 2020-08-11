package com.github.tommyettinger;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.TimeUtils;
import com.github.tommyettinger.anim8.*;

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
        png8.palette = new PaletteReducer(new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF});
        png8.setFlipY(false);
        png8.setCompression(7);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
        png8.write(Gdx.files.local("images/" + name + "/PNG8-" + name + "-BW-None.png"), pixmaps, 20);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
        png8.write(Gdx.files.local("images/" + name + "/PNG8-" + name + "-BW-Diffusion.png"), pixmaps, 20);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
        png8.write(Gdx.files.local("images/" + name + "/PNG8-" + name + "-BW-Pattern.png"), pixmaps, 20);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
        png8.write(Gdx.files.local("images/" + name + "/PNG8-" + name + "-BW-GradientNoise.png"), pixmaps, 20);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
        png8.write(Gdx.files.local("images/" + name + "/PNG8-" + name + "-BW-BlueNoise.png"), pixmaps, 20);
    }

    public void renderGif() {
        Array<Pixmap> pixmaps = new Array<>(90);
        for (int i = 1; i <= 90; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".jpg")));
        }
        AnimatedGif gif = new AnimatedGif();
        //// BW
//        gif.palette = new PaletteReducer(new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF});
        //// GB-16 Green
//        gif.palette = new PaletteReducer(new int[]{0x00000000, 
//                0x000000FF, 0x081820FF, 0x132C2DFF, 0x1E403BFF, 0x295447FF, 0x346856FF, 0x497E5BFF, 0x5E9463FF, 
//                0x73AA69FF, 0x88C070FF, 0x9ECE88FF, 0xB4DCA0FF, 0xCAEAB8FF, 0xE0F8D0FF, 0xEFFBE7FF, 0xFFFFFFFF});
        gif.setFlipY(false);
//        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
//        gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + name + "-Green-None.gif"), pixmaps, 20);
//        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
//        gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + name + "-Green-Diffusion.gif"), pixmaps, 20);
//        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
//        gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + name + "-Green-Pattern.gif"), pixmaps, 20);
//        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
//        gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + name + "-Green-GradientNoise.gif"), pixmaps, 20);
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
        gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + name + "-BlueNoise.gif"), pixmaps, 20);
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
