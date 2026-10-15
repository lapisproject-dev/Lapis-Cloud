// V1.9.142 "Kamera-Filter": the camera filter stage runs a WebGL2 shader on an OffscreenCanvas. A headless Chrome without a GPU has no WebGL at
// all, which silently skipped every GPU test (ConferenceCameraFilterStageTest returns early without WebGL2). SwiftShader is Chrome's own
// software rasteriser: with these flags the real shader runs on real pixels in CI and on a Mac without a display. The tests still
// return early where even this is unavailable -- the flags only widen what is verified, they never gate the build.
config.customLaunchers = Object.assign({}, config.customLaunchers, {
    ChromeHeadlessSoftwareGl: {
        base: 'ChromeHeadless',
        flags: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--enable-webgl'],
    },
});
config.browsers = ['ChromeHeadlessSoftwareGl'];
