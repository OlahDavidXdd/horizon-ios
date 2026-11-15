# Horizon GlassNav

The bottom bar bundles the actual `GlassNav` from
[zakisheriff/Liquid-Glass](https://github.com/zakisheriff/Liquid-Glass), npm
`@zakisheriff/liquid-glass` 4.0.0 (published git revision
`edac4eebdb24118d3a099fcfa0a1564f944a624e`), with upstream source inspected at revision
`e5bb9b02a4ee69d6f220c887adccf0f55a8058b7` (MIT).

Run `npm ci` then `npm run build` here. The lockfile pins the package and React;
the generated HTML and license files are committed under the app's assets so
Android builds need no npm or network connection for the navigation UI.

A local, network-blocked WebView hosts this web component; the rest of the app
remains native. Its only JavaScript interface methods are `ready()` and a
range-checked `select(index)`. Native tab changes and translated labels update
the component. The bar refracts a capture of the native gradient behind its dock;
no account data, page text, or game controls enter the WebView. A renderer failure
falls back to native navigation. Reduce Transparency disables refraction.

Android owns the MotionEvent stream and forwards normalized coordinates into the bundled component. WebView gesture arbitration is bypassed for the dock; keyboard/accessibility tab actions still use GlassNav. The lifted lens edges accept a press, and lifecycle cancellation clears unfinished drags.
