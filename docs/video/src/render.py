from playwright.sync_api import sync_playwright
import pathlib, time, sys

BASE = "/root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d69-851252c9d000/video"
OUT = pathlib.Path(BASE) / "frames"
OUT.mkdir(exist_ok=True)
FPS = 30
DUR = 90
N = FPS * DUR

t0 = time.time()
with sync_playwright() as pw:
    b = pw.chromium.launch(executable_path="/usr/bin/chromium",
        args=["--no-sandbox", "--disable-dev-shm-usage", "--force-color-profile=srgb",
              "--disable-lcd-text", "--font-render-hinting=none", "--hide-scrollbars"])
    pg = b.new_page(viewport={"width": 1920, "height": 1080}, device_scale_factor=1)
    pg.goto(f"file://{BASE}/scene.html")
    pg.wait_for_timeout(800)
    pg.evaluate("() => document.fonts.ready")
    pg.wait_for_timeout(300)

    for i in range(N):
        t = i / FPS
        pg.evaluate(
            "t => { window.renderAt(t); return new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))); }",
            t,
        )
        pg.screenshot(path=str(OUT / f"{i:05d}.jpg"), type="jpeg", quality=93)
        if i % 150 == 0:
            el = time.time() - t0
            print(f"{i}/{N}  {el:.1f}s  eta {el/max(i,1)*(N-i):.0f}s", flush=True)

    b.close()
print(f"DONE {N} frames in {time.time()-t0:.1f}s")
