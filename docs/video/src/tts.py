import asyncio, edge_tts, subprocess, json
LINES = [
 ("01", 0.6,  "电脑关了。它还在跑。"),
 ("02", 5.0,  "这是一块手表，里面装的是完整的 Agent。工具循环跑在表盘上，不是遥控器。"),
 ("03", 12.4, "你给它一个任务，它自己拆步骤、调工具、读文件，做完一步再想下一步。"),
 ("04", 26.2, "读写文件、跑脚本、搜网页，全程在这块表上完成。旁边不需要有台电脑。"),
 ("05", 40.2, "接了 Shizuku，它就能用 adb 的权限干活。每一步都写清楚用途，你点头它才动。"),
 ("06", 54.2, "碰到删系统文件这种，它会拦两次。第一次不执行任何东西，只是让你看清楚。"),
 ("07", 66.2, "它还能自己操作这台设备。翻页、点按、改设置，你随时能按停。"),
 ("08", 78.2, "别人的手表，是电脑的遥控器。这块手表，自己就是电脑。"),
 ("09", 88.1, "开源，链接在简介。"),
]
async def main():
    out=[]
    for name, start, text in LINES:
        path=f"vo/{name}.mp3"
        c = edge_tts.Communicate(text, "zh-CN-YunxiNeural", rate="+10%")
        await c.save(path)
        d = float(subprocess.check_output(["ffprobe","-v","quiet","-show_entries","format=duration","-of","csv=p=0",path]).decode().strip())
        out.append((name,start,d,text))
        print(f"{name} start={start:5.1f} dur={d:5.2f} end={start+d:5.2f}")
    json.dump([{"f":n,"start":s,"dur":d,"text":t} for n,s,d,t in out], open("vo/meta.json","w"), ensure_ascii=False, indent=1)
asyncio.run(main())
