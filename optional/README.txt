OPTIONAL FILES

The optional folder here contains any files that you can include in your own project to enable optional features.

The only file here currently is BigPaletteMapping.dat ; this file can be copied into your resources root (which is
typically `/assets/` in a libGDX project) to allow using the `analyzeReductive()` methods in PaletteReducer,
FastPalette, and QualityPalette. Those methods sometimes might have better results than `QualityPalette.analyze()`, but
usually don't have results that are especially good. So, it's an optional feature.
