# 90 秒演示片

`dshwatch-90s.mp4` · 1920×1080 · 30fps · H.264 + AAC · 90.0s · 15MB

画面全部由 `src/scene.html` 逐帧渲染，没有一张实拍素材。配音由 edge-tts 生成，按秒数对齐后在 ffmpeg 里做侧链混音（口播压底噪）。

## 内容

| 时间 | 画面 | 字幕 |
|---|---|---|
| 0–4 | 笔记本合盖，屏幕熄灭 | 电脑关了。 |
| 4–12 | 手表升起，任务在跑 | 它还在跑。 |
| 12–26 | 读 PDF → 工具卡片 → 流式回答 | 52 个工具，在表盘上执行 |
| 26–40 | 改版本号 → diff → 写入成功 | 读文件、改代码，不需要电脑 |
| 40–54 | `dumpsys battery` → Shizuku 审批卡 | Shizuku 提权，用途写清楚才执行 |
| 54–66 | 危险命令两屏 → 取消 → 已拦截 | 危险操作，两次确认 |
| 66–78 | 设置页，光标点开「显示」，亮度拉满 | AI 自己操作这台设备 |
| 78–88 | 分屏对比：遥控方案 vs 本地引擎 | 别人的手表是遥控器，这块是主机 |
| 88–90 | Logo + GitHub | 开源 · GPL-3.0 |

## 改内容

全部时间点和文案都在 `src/scene.html` 的两个对象里：

```js
const T = { aType:[12.3,14.6], ... };   // 每个动作的起止秒
const SUBS = [ [0,4.4,'电脑关了。'], ... ];  // 字幕
```

`T` 里每个键是 `[开始, 结束]`，单位秒。改完直接重跑，不用动其它文件。

## 重新出片

```bash
# 1. 需要 playwright + chromium + ffmpeg
pip install playwright

# 2. 改 src/render.py 顶部的 BASE 路径为当前目录
python3 render.py          # 2700 帧，约 5 分钟
python3 tts.py             # 重新生成配音
bash mix.sh                # 混音

ffmpeg -y -framerate 30 -i frames/%05d.jpg -i vo/final.m4a \
  -c:v libx264 -crf 19 -pix_fmt yuv420p \
  -c:a aac -b:a 192k -shortest -movflags +faststart out.mp4
```

本机 ffmpeg 没有 libx264 时用 `-c:v libopenh264 -b:v 9M`（本片即如此，码率约 1.3Mbps，文字仍清晰）。

## 已知取舍

- 手表 UI 是按 v1.21.1 的实际界面重画的，不是真机录屏。好处是字大、可控、4K 无损失；坏处是少了真机质感。
- 正式投稿如果想让画面更硬，把 12–40 秒两段换成真机录屏（见 `../video-plan.md` 的素材清单 A6–A9），其余段落保留动画即可。
- 配音是合成的，语气平稳但没有情绪起伏。要更抓人，建议自己按 `vo/meta.json` 的时间点重录一遍。
