package com.github.tommyettinger;

import com.badlogic.gdx.*;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.scenes.scene2d.utils.UIUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.github.tommyettinger.anim8.Dithered;
import com.github.tommyettinger.anim8.FastPalette;
import com.github.tommyettinger.anim8.PaletteReducer;
import com.github.tommyettinger.anim8.QualityPalette;

public class InteractiveQualityReducer extends ApplicationAdapter {
    public static final int SCREEN_WIDTH = 1280;
    public static final int SCREEN_HEIGHT = 720;
    public static final int THRESHOLD = 100;
    public static final int REDUCTIVE_THRESHOLD = 300;
    protected SpriteBatch batch;
    protected Viewport screenView;
    protected Texture screenTexture;
    protected BitmapFont font;
    protected QualityPalette reducerQ;
    protected FastPalette reducerF;
    private PaletteReducer reducer;
    private int[] palette, altPalette, eightPalette;
    private Pixmap p0, p;
    private final int algorithmCount = Dithered.DitherAlgorithm.ALL.length;
    private int index = algorithmCount - 1;
    private float strength = 1f;

    public static void main(String[] arg) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Palette Reducer");
        config.setWindowedMode(SCREEN_WIDTH, SCREEN_HEIGHT);
        config.setIdleFPS(10);
        config.useVsync(true);
        config.setResizable(true);
        final InteractiveQualityReducer app = new InteractiveQualityReducer();
        config.setWindowListener(new Lwjgl3WindowAdapter() {
            @Override
            public void filesDropped(String[] files) {
                if (files != null && files.length > 0) {
                    if (files[0].endsWith(".png") || files[0].endsWith(".jpg") || files[0].endsWith(".jpeg"))
                        app.load(files[0]);
                    else if(files[0].endsWith("hex"))
                        app.loadPalette(files[0]);
                }
            }
        });

        new Lwjgl3Application(app, config);
    }

    public void load(String name) {
        if(p0 != null) p0.dispose();
        //// loads a file by its full path, which we get via drag+drop
        if (Gdx.files.internal(name).exists())
            p0 = new Pixmap(Gdx.files.internal(name));
        else
            p0 = new Pixmap(Gdx.files.absolute(name));
        if(p != null) p.dispose();
        p = new Pixmap(this.p0.getWidth(), this.p0.getHeight(), Pixmap.Format.RGBA8888);
        screenTexture = new Texture(p);
        refresh();
    }
    public void loadPalette(String name) {
        try {
            if(name == null || name.isEmpty()) return;
            FileHandle fh = Gdx.files.absolute(name);
            String text;
            if(fh.exists() && "hex".equals(fh.extension()))
                text = fh.readString();
            else
                return;
            int start = 0, end = 6, len = text.length();
            int gap = (text.charAt(7) == '\n') ? 8 : 7;
            int sz = ((len + 2) / gap);
            palette = new int[sz + 1];
            for (int i = 1; i <= sz; i++) {
                palette[i] = Integer.parseInt(text.substring(start, end), 16) << 8 | 0xFF;
                start += gap;
                end += gap;
            }
            reducerQ.exact(palette);
            reducerF.exact(palette);
        } catch (GdxRuntimeException e) {
            e.printStackTrace();
        }
    }


    public void refresh(){
        p.drawPixmap(this.p0, 0, 0);
        reducer.reduce(p, Dithered.DitherAlgorithm.ALL[index]);
        screenTexture.draw(p, 0, 0);
    }

    @Override
    public void create() {
        font = new BitmapFont();
        batch = new SpriteBatch();
        palette = new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF};
        altPalette = new int[]{0x00000000, 0x081820FF, 0x346856FF, 0x88C070FF, 0xE0F8D0FF};
//        altPalette = new int[]{
//                0x00000000, 0x000000ff, 0x222222ff, 0x1f2417ff, 0x433b46ff, 0x545454ff, 0x8c8c8cff, 0xe1e1e1ff,
//                0xeaf2f0ff, 0xf6f9ffff, 0xfcf6ffff, 0xfff7f9ff, 0xffffffff, 0x24151aff, 0x33061aff, 0x3d272dff,
//                0x47383cff, 0x81696fff, 0xd7b3bcff, 0xd7c1c7ff, 0xffe3ebff, 0xffeff3ff, 0xfff0f4ff, 0xb1707eff,
//                0xecaab6ff, 0xfc6b8aff, 0x420319ff, 0xf03663ff, 0x7e454dff, 0xc96674ff, 0xfc8f9dff, 0xd3aeb1ff,
//                0x571121ff, 0x640020ff, 0x331216ff, 0x982038ff, 0x903240ff, 0xa8062aff, 0xf92845ff, 0x311b1aff,
//                0x523635ff, 0xcb6362ff, 0x4a000bff, 0x473b3aff, 0x642c28ff, 0xd6b5b1ff, 0xfe887aff, 0xad594cff,
//                0x690d00ff, 0x8c6962ff, 0x2c1e1aff, 0xc12500ff, 0x806b66ff, 0xa16052ff, 0xbc4f3aff, 0x8a2007ff,
//                0x7f2d1bff, 0x513932ff, 0xf1ab98ff, 0xc34421ff, 0x3f1000ff, 0xff6837ff, 0x331404ff, 0x5a3423ff,
//                0x655046ff, 0x87685aff, 0xb48977ff, 0xffe2d5ff, 0xfff6f2ff, 0x9d6447ff, 0xa94803ff, 0xe5793fff,
//                0xffc19cff, 0x6b3000ff, 0x925125ff, 0x372a21ff, 0xd96e10ff, 0xd87314ff, 0x382310ff, 0x433b34ff,
//                0xc48e5eff, 0xfce3ceff, 0x6b4f32ff, 0xf5a54cff, 0xb1742bff, 0xc17600ff, 0x2d1d08ff, 0x4a2e02ff,
//                0x7d7164ff, 0xd3c5b5ff, 0x805004ff, 0xffe1b9ff, 0xeda110ff, 0xcdb486ff, 0x977e50ff, 0x543d0bff,
//                0x7b6027ff, 0xffdd98ff, 0x9f7d34ff, 0xdaaf53ff, 0xa87c00ff, 0x473f2aff, 0x797057ff, 0xe0af1aff,
//                0xfff0caff, 0xfaf3e0ff, 0xfef4daff, 0xffd651ff, 0xfde9a0ff, 0xc7b981ff, 0x262000ff, 0xfff6c6ff,
//                0x726621ff, 0x4b430dff, 0x8d8129ff, 0x938600ff, 0x232113ff, 0xc1b953ff, 0xfef689ff, 0xfff871ff,
//                0xc5bd1dff, 0x898978ff, 0xf0f4d0ff, 0xeaf5baff, 0xd9fc61ff, 0x718d12ff, 0x344210ff, 0x3d4233ff,
//                0x75894fff, 0xbaf822ff, 0x305207ff, 0x132407ff, 0xeffbeaff, 0x193f06ff, 0x003000ff, 0xa0d49aff,
//                0x0c4e0bff, 0x17de2eff, 0x0b250dff, 0x97ef97ff, 0x204426ff, 0x117e3aff, 0x497a54ff, 0x223d2bff,
//                0x6d8473ff, 0xd7e4daff, 0xf2fff5ff, 0x375041ff, 0x466756ff, 0x67febfff, 0x0a3929ff, 0xd7f5e7ff,
//                0x006f50ff, 0x235b47ff, 0x092018ff, 0x0f261eff, 0x5ca288ff, 0x32d5a7ff, 0x889d98ff, 0xbcfff0ff,
//                0x00756bff, 0x30e4dbff, 0x0d3536ff, 0x155f62ff, 0x519c9eff, 0xc0f7f8ff, 0x092023ff, 0x00badfff,
//                0xbbedfdff, 0x001e28ff, 0x065c78ff, 0x599cb8ff, 0x006d92ff, 0x093345ff, 0xb1d8fdff, 0x1a5081ff,
//                0x338de0ff, 0x3c5063ff, 0x5d9bddff, 0x082442ff, 0xdfeeffff, 0x205193ff, 0x111a25ff, 0x222c39ff,
//                0x1c469dff, 0x092059ff, 0x7f94bfff, 0x383c49ff, 0x6d7281ff, 0x7281cfff, 0x383ca5ff, 0x31365dff,
//                0x7b83b5ff, 0x1c1856ff, 0x696fedff, 0xced5ffff, 0x331bacff, 0x353772ff, 0x171539ff, 0xe3e4ffff,
//                0xc2c1d4ff, 0x8a62e6ff, 0x492c7fff, 0x9077ceff, 0xded3feff, 0x443168ff, 0x8f7db5ff, 0x423659ff,
//                0x6017aeff, 0x2d0352ff, 0x251637ff, 0xebe2f9ff, 0x270d32ff, 0x310341ff, 0x3d2448ff, 0x7b6e81ff,
//                0xa587b3ff, 0xb684ceff, 0xd4c4dcff, 0xf5eafbff, 0xcc82e7ff, 0x783889ff, 0x4a1557ff, 0x634d67ff,
//                0x714479ff, 0x9f00b9ff, 0xf7d9fcff, 0x211523ff, 0x3a2c3cff, 0x2c0d2dff, 0x380034ff, 0x79406fff,
//                0xa17598ff, 0xffc0f3ff, 0xb11c99ff, 0x8a3078ff, 0x570f4aff, 0xb769a3ff, 0x4a213fff, 0x704863ff,
//                0xe296c5ff, 0xfce7f3ff, 0xf77fcaff, 0x3b052cff, 0xd64fa6ff, 0xffd0eaff, 0xd19eb8ff, 0x310720ff,
//                0xc76193ff, 0x67003fff, 0x511835ff, 0x8a365dff, 0xb4166aff, 0x93275aff, 0x784358ff, 0xb4708aff,
//        };
        eightPalette = new int[]{
                0x00000000,
                0x000000FF, 0x55415FFF, 0x646964FF, 0xD77355FF, 0x508CD7FF, 0x64B964FF, 0xE6C86EFF, 0xDCF5FFFF,
        };
        reducerQ = new QualityPalette(eightPalette);
        reducerF = new FastPalette(eightPalette);
//        reducer = new PaletteReducer(PaletteReducer.HALTONIC);
//        reducer.writePreloadFile(Gdx.files.local("haltonic.txt"));
        reducerQ.setDitherStrength(strength);
        reducerF.setDitherStrength(strength);
        reducer = reducerQ;
        screenView = new ScreenViewport();
        screenView.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        batch.enableBlending();
        Gdx.input.setInputProcessor(inputProcessor());

        load("Cat.jpg");
    }


    @Override
    public void render() {
        Gdx.gl.glClearColor(0.4f, 0.4f, 0.4f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        refresh();
        batch.setProjectionMatrix(screenView.getCamera().combined);
        batch.begin();
        if(screenTexture != null)
            batch.draw(screenTexture, 0, 0);
        else {
            font.draw(batch, "Drag and drop an image file onto this window;", 20, 150);
            font.draw(batch, "a palette-reduced copy will be shown here.", 20, 120);
        }
        batch.end();
        Gdx.graphics.setTitle(Gdx.graphics.getFramesPerSecond() + " FPS");
    }

    @Override
    public void resize(int width, int height) {
        screenView.update(width, height, true);
    }

    public InputProcessor inputProcessor() {
        return new InputAdapter() {
            @Override
            public boolean keyDown(int keycode) {
                switch (keycode) {
                    case Input.Keys.SLASH:
                        reducer = (reducer == reducerQ ? reducerF : reducerQ);
                        break;
                    case Input.Keys.C:
                        load("Cat.jpg");
                        break;
                    case Input.Keys.M:
                        load("Mona_Lisa.jpg");
                        break;
                    case Input.Keys.L:
                        load("Landscape.jpg");
                        break;
                    case Input.Keys.F:
                        load("Frog.jpg");
                        break;
                    case Input.Keys.P:
                        load("Pixel_Art.png");
                        break;
                    case Input.Keys.A:
                        reducerQ.analyze(p0);
                        reducerF.analyze(p0);
                        refresh();
                        break;
                    case Input.Keys.NUM_1:
                    case Input.Keys.NUM_2:
                    case Input.Keys.NUM_3:
                    case Input.Keys.NUM_4:
                    case Input.Keys.NUM_5:
                    case Input.Keys.NUM_6:
                    case Input.Keys.NUM_7:
                    case Input.Keys.NUM_8:
                    case Input.Keys.NUM_9:
//                        reducerQ.totalDifference = 0.0;
                        if(UIUtils.ctrl()) {
                            if (UIUtils.shift()) {
                                int kc = (keycode - 6) * keycode;
                                reducerQ.analyzeReductive(p0, REDUCTIVE_THRESHOLD, kc);
                                reducerF.analyzeReductive(p0, REDUCTIVE_THRESHOLD, kc);
                            }
                            else {
                                int kc = keycode - 5;
                                reducerQ.analyzeReductive(p0, REDUCTIVE_THRESHOLD, kc);
                                reducerF.analyzeReductive(p0, REDUCTIVE_THRESHOLD, kc);
                            }

//                            if (UIUtils.shift())
//                                reducer.exact(PaletteReducer.HALTONIC, (keycode - 6) * keycode);
//                            else
//                                reducer.exact(PaletteReducer.HALTONIC, keycode - 5);

//                            if (UIUtils.shift())
//                                reducer.analyzeMC(p0, (keycode - 6) * keycode);
//                            else
//                                reducer.analyzeMC(p0, keycode - 5);
                        }
                        else {
                            if (UIUtils.shift()) {
                                int kc = (keycode - 6) * keycode;
                                reducerQ.analyze(p0, THRESHOLD, kc);
                                reducerF.analyze(p0, THRESHOLD, kc);
                            }
                            else {
                                int kc = keycode - 5;
                                reducerQ.analyze(p0, THRESHOLD, kc);
                                reducerF.analyze(p0, THRESHOLD, kc);
                            }
                        }
//                        System.out.println("Total for all color differences: " + reducerQ.totalDifference);
                        refresh();
                        break;
                    case Input.Keys.NUM_0:
                        if(UIUtils.ctrl())
                        {
                            reducerQ.analyzeReductive(p0, REDUCTIVE_THRESHOLD, 256);
                            reducerF.analyzeReductive(p0, REDUCTIVE_THRESHOLD, 256);
                        }
                        else
                        {
                            reducerQ.analyze(p0, THRESHOLD);
                            reducerF.analyze(p0, THRESHOLD);
                        }
                        refresh();
                        break;
                    case Input.Keys.B:
                        if(UIUtils.shift())
                        {
                            reducerQ.exact(altPalette);
                            reducerF.exact(altPalette);
                        }
                        else
                        {
                            reducerQ.exact(palette);
                            reducerF.exact(palette);
                        }
                        refresh();
                        break;
                    case Input.Keys.D:
                        if(UIUtils.shift())
                        {
                            reducerQ.exact(eightPalette);
                            reducerF.exact(eightPalette);
                        }
                        else
                        {
                            reducerQ.setDefaultPalette();
                            reducerF.setDefaultPalette();
                        }
                        refresh();
                        break;
                    case Input.Keys.LEFT:
                    case Input.Keys.LEFT_BRACKET:
                        index = (index + algorithmCount - 1) % algorithmCount;
                        refresh();
                        break;
                    case Input.Keys.RIGHT:
                    case Input.Keys.RIGHT_BRACKET:
                        index = (index + 1) % algorithmCount;
                        refresh();
                        break;
                    case Input.Keys.UP:
                        reducerQ.setDitherStrength(strength = Math.min(strength + 0.05f, 2f));
                        reducerF.setDitherStrength(strength);
                        refresh();
                        break;
                    case Input.Keys.DOWN:
                        reducerQ.setDitherStrength(strength = Math.max(strength - 0.05f, 0f));
                        reducerF.setDitherStrength(strength);
                        refresh();
                        break;
                    case Input.Keys.S:
                        System.out.println("Using " + Dithered.DitherAlgorithm.ALL[index] + ", strength: " + strength
                                + ", colors: " + reducer.colorCount +
                                (reducer == reducerQ ? " with a QualityPalette" : " with a FastPalette"));
//                        System.out.println(Gdx.app.getJavaHeap() + " bytes in the Java heap.");
//                        System.out.println(Gdx.app.getNativeHeap() + " bytes in the native heap.");
                        break;
                    case Input.Keys.Q:
                    case Input.Keys.ESCAPE:
                        Gdx.app.exit();
                        break;
                }
                return true;
            }
        };
    }
}
